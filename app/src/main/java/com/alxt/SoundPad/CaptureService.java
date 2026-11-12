package com.alxt.SoundPad;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.io.*;

public class CaptureService extends Service {
    private MediaProjection projection;
    private AudioRecord recorder;
    private volatile boolean recording;
    private Thread writerThread;
    private AudioTrack monitorTrack;
    private final Object lock = new Object();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("STOP".equals(intent.getAction())) {
            stopCapture();
            return START_NOT_STICKY;
        }
        if (!"START".equals(intent.getAction())) return START_NOT_STICKY;

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
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord failed to initialize");
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Audio capture did not start");
            }
            int outMin = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            monitorTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(outMin * 2, 32768))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            if (monitorTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Live audio monitor failed to initialize");
            }
            monitorTrack.play();
            recording = true;
            writerThread = new Thread(this::writeCapture, "SoundPad-capture-writer");
            writerThread.start();
        } catch (Exception e) {
            stopCapture();
            stopSelf();
        }
        return START_NOT_STICKY;
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
                    if (monitor != null) monitor.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING);
                }
                else if (count < 0 && count != AudioRecord.ERROR_INVALID_OPERATION) {
                    throw new IOException("AudioRecord read error " + count);
                }
            }
            stream.flush();
        } catch (Exception e) {
            // The partial recording remains playable if at least one buffer was written.
        } finally {
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
        }
        if (active != null) {
            try { active.stop(); } catch (Exception ignored) {}
            try { active.release(); } catch (Exception ignored) {}
        }
        AudioTrack activeMonitor;
        synchronized (lock) {
            activeMonitor = monitorTrack;
            monitorTrack = null;
        }
        if (activeMonitor != null) {
            try { activeMonitor.pause(); } catch (Exception ignored) {}
            try { activeMonitor.flush(); } catch (Exception ignored) {}
            try { activeMonitor.stop(); } catch (Exception ignored) {}
            try { activeMonitor.release(); } catch (Exception ignored) {}
        }
        if (activeProjection != null) {
            try { activeProjection.stop(); } catch (Exception ignored) {}
        }
        stopForeground(true);
    }

    @Override public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
