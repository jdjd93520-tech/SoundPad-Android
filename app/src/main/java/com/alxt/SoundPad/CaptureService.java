package com.alxt.SoundPad;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.io.*;

public class CaptureService extends Service {
    private static final String PREFS="soundpad_audio_settings";
    private static final String PREF_MONITOR="monitor_enabled";
    private static final String PREF_OUTPUT="output_device_id";
    private MediaProjection projection;
    private AudioRecord recorder;
    private volatile boolean recording;
    private Thread writerThread;
    private AudioTrack monitorTrack;
    private final Object lock = new Object();
    private boolean monitorEnabled=true;
    private int preferredOutputId=0;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action=intent.getAction();
        if ("STOP".equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("SET_MONITOR".equals(action)) {
            monitorEnabled=intent.getBooleanExtra("monitor_enabled",true);
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(PREF_MONITOR,monitorEnabled).apply();
            updateMonitor();
            return START_NOT_STICKY;
        }
        if ("SET_OUTPUT_DEVICE".equals(action)) {
            preferredOutputId=intent.getIntExtra("output_device_id",0);
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(PREF_OUTPUT,preferredOutputId).apply();
            synchronized(lock) {
                if(monitorTrack!=null) {
                    AudioDeviceInfo device=findOutput(preferredOutputId);
                    monitorTrack.setPreferredDevice(device);
                }
            }
            return START_NOT_STICKY;
        }
        if (!"START".equals(action)) return START_NOT_STICKY;
        if(recording)return START_NOT_STICKY;

        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        monitorEnabled=prefs.getBoolean(PREF_MONITOR,true);
        preferredOutputId=prefs.getInt(PREF_OUTPUT,0);
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "sp")
            .setContentTitle("SoundPad recording")
            .setContentText("Capturing permitted internal audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(2, notification);

        try {
            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(intent.getIntExtra("code", 0),
                (Intent)intent.getParcelableExtra("data"));
            if (projection == null) throw new IllegalStateException("MediaProjection permission unavailable");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopCapture(); stopSelf(); }
            },new Handler(Looper.getMainLooper()));

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, 32768))
                .setAudioPlaybackCaptureConfig(config).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord failed to initialize");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Audio capture did not start");

            recording = true;
            updateMonitor();
            writerThread = new Thread(this::writeCapture, "SoundPad-capture-writer");
            writerThread.start();
        } catch (Exception e) {
            stopCapture();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private AudioDeviceInfo findOutput(int id) {
        if(id==0)return null;
        AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(manager!=null)for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getId()==id)return d;
        return null;
    }

    private void updateMonitor() {
        synchronized(lock) {
            if(!recording || !monitorEnabled) {
                releaseMonitorLocked();
                return;
            }
            if(monitorTrack!=null)return;
            int outMin=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track=null;
            try {
                track=new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(outMin*2,32768))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Audio monitor could not initialize");
                AudioDeviceInfo preferred=findOutput(preferredOutputId);
                if(preferredOutputId!=0 && preferred==null)preferredOutputId=0;
                track.setPreferredDevice(preferred);
                track.play();
                monitorTrack=track;
            } catch(Exception e) {
                if(track!=null)try{track.release();}catch(Exception ignored){}
                monitorTrack=null;
            }
        }
    }

    private void releaseMonitorLocked() {
        AudioTrack t=monitorTrack;monitorTrack=null;
        if(t!=null) {
            try{t.pause();}catch(Exception ignored){}
            try{t.flush();}catch(Exception ignored){}
            try{t.stop();}catch(Exception ignored){}
            try{t.release();}catch(Exception ignored){}
        }
    }

    private void writeCapture() {
        File out = new File(getFilesDir(), "capture.pcm");
        byte[] buffer = new byte[16384];
        try (FileOutputStream stream = new FileOutputStream(out, false)) {
            while (recording) {
                AudioRecord active;
                synchronized (lock) { active = recorder; }
                if (active == null) break;
                int count = active.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    stream.write(buffer, 0, count);
                    AudioTrack monitor;
                    synchronized (lock) { monitor = monitorTrack; }
                    if (monitor != null) {
                        try { monitor.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING); }
                        catch(Exception ignored) { }
                    }
                } else if (count < 0 && count != AudioRecord.ERROR_INVALID_OPERATION) {
                    throw new IOException("AudioRecord read error " + count);
                }
            }
            stream.flush();
        } catch (Exception ignored) {
            // Keep the partial PCM file available when capture stops or an app blocks capture.
        } finally {
            stopCapture();
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel("sp", "SoundPad recording",
                NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void stopCapture() {
        recording = false;
        AudioRecord active;
        MediaProjection activeProjection;
        synchronized (lock) {
            active = recorder;
            recorder = null;
            activeProjection = projection;
            projection = null;
            releaseMonitorLocked();
        }
        if (active != null) {
            try { active.stop(); } catch (Exception ignored) {}
            try { active.release(); } catch (Exception ignored) {}
        }
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
        }
        stopForeground(true);
    }

    @Override public void onDestroy() { stopCapture(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}