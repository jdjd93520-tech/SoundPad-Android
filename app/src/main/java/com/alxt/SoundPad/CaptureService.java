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
    private final Object lock = new Object();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("STOP".equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!"START".equals(intent.getAction()) || recording) return START_NOT_STICKY;

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "sp")
            .setContentTitle("SoundPad recording")
            .setContentText("Capturing app audio when the source permits capture")
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
                @Override public void onStop() {
                    stopCapture();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("This device does not support the requested capture format");
            AudioRecord created = new AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, 32768))
                .setAudioPlaybackCaptureConfig(config).build();
            if (created.getState() != AudioRecord.STATE_INITIALIZED) {
                created.release();
                throw new IllegalStateException("AudioRecord failed to initialize");
            }
            synchronized (lock) { recorder = created; }
            File temporary = new File(getFilesDir(), "capture.pcm.tmp");
            if (temporary.exists()) temporary.delete();
            created.startRecording();
            if (created.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Audio capture did not start");
            }
            recording = true;
            writerThread = new Thread(() -> writeCapture(temporary), "SoundPad-capture-writer");
            writerThread.start();
        } catch (Exception e) {
            stopCapture();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void writeCapture(File temporary) {
        byte[] buffer = new byte[16384];
        long written = 0;
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            while (recording) {
                AudioRecord active;
                synchronized (lock) { active = recorder; }
                if (active == null) break;
                int count = active.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    stream.write(buffer, 0, count);
                    written += count;
                } else if (count < 0 && recording) {
                    throw new IOException("AudioRecord read error " + count);
                }
            }
            stream.flush();
            stream.getFD().sync();
        } catch (Exception ignored) {
            // Keep the last successfully finalized recording instead of replacing it with a broken file.
        } finally {
            if (written > 0 && temporary.exists()) {
                File destination = new File(getFilesDir(), "capture.pcm");
                if (destination.exists()) destination.delete();
                if (!temporary.renameTo(destination)) temporary.delete();
            } else {
                temporary.delete();
            }
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
        Thread writer;
        synchronized (lock) {
            active = recorder;
            activeProjection = projection;
            writer = writerThread;
        }
        if (active != null) {
            try { active.stop(); } catch (Exception ignored) {}
        }
        if (writer != null && writer != Thread.currentThread()) {
            try { writer.join(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        synchronized (lock) {
            if (recorder == active) recorder = null;
            if (projection == activeProjection) projection = null;
            if (writerThread == writer && (writer == null || !writer.isAlive())) writerThread = null;
        }
        if (active != null) {
            try { active.release(); } catch (Exception ignored) {}
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