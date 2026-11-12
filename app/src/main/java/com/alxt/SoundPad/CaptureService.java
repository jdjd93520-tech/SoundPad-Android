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
    private volatile ParcelFileDescriptor injectionPipe;
    private volatile OutputStream injectionOutput;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("STOP_INJECTION".equals(intent.getAction())) {
            closeInjectionPipe();
            return START_NOT_STICKY;
        }
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
            if (Build.VERSION.SDK_INT >= 33) {
                injectionPipe = intent.getParcelableExtra("inject_pipe", ParcelFileDescriptor.class);
            } else {
                injectionPipe = intent.getParcelableExtra("inject_pipe");
            }
            if (injectionPipe != null) injectionOutput = new FileOutputStream(injectionPipe.getFileDescriptor());

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
                    OutputStream pipe = injectionOutput;
                    if (pipe != null) {
                        try {
                            pipe.write(buffer, 0, count);
                            pipe.flush();
                        } catch (IOException pipeError) {
                            closeInjectionPipe();
                        }
                    }
                } else if (count < 0 && count != AudioRecord.ERROR_INVALID_OPERATION) {
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

    private void closeInjectionPipe() {
        OutputStream output = injectionOutput;
        injectionOutput = null;
        if (output != null) try { output.close(); } catch (Exception ignored) {}
        ParcelFileDescriptor pipe = injectionPipe;
        injectionPipe = null;
        if (pipe != null) try { pipe.close(); } catch (Exception ignored) {}
    }

    private void stopCapture() {
        recording = false;
        closeInjectionPipe();
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