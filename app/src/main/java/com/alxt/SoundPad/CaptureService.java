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
    private volatile Thread writerThread;
    private final Object lock = new Object();
    private volatile ParcelFileDescriptor injectionPipe;
    private volatile OutputStream injectionOutput;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if ("STOP_INJECTION".equals(action)) {
            closeInjectionPipe();
            reportStatus("Microphone forwarding stopped; internal recording can continue.");
            return START_NOT_STICKY;
        }
        if ("STOP".equals(action)) {
            stopCapture();
            reportStatus("Internal audio recording stopped.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!"START".equals(action)) return START_NOT_STICKY;

        // A second Start must not leave an older recorder or projection alive.
        stopCapture();
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "sp")
            .setContentTitle("SoundPad recording")
            .setContentText("Capturing permitted internal audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(2, notification);
        }

        try {
            MediaProjectionManager manager =
                (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjection service unavailable");

            if (Build.VERSION.SDK_INT >= 33) {
                injectionPipe = intent.getParcelableExtra("inject_pipe", ParcelFileDescriptor.class);
            } else {
                injectionPipe = intent.getParcelableExtra("inject_pipe");
            }
            if (injectionPipe != null) {
                injectionOutput = new FileOutputStream(injectionPipe.getFileDescriptor());
            }

            projection = manager.getMediaProjection(intent.getIntExtra("code", 0),
                (Intent)intent.getParcelableExtra("data"));
            if (projection == null) {
                throw new IllegalStateException("MediaProjection permission unavailable");
            }

            AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
            int min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("Android returned invalid capture buffer size: " + min);

            AudioRecord newRecorder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, 32768))
                .setAudioPlaybackCaptureConfig(config)
                .build();
            if (newRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                newRecorder.release();
                throw new IllegalStateException("AudioRecord failed to initialize");
            }
            recorder = newRecorder;
            newRecorder.startRecording();
            if (newRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Audio capture did not enter recording state");
            }

            recording = true;
            Thread thread = new Thread(() -> writeCapture(startId), "SoundPad-capture-writer");
            writerThread = thread;
            thread.start();
            reportStatus(injectionOutput == null
                ? "Internal audio recording started; waiting for eligible playback."
                : "Internal audio recording and experimental microphone forwarding started.");
        } catch (Exception e) {
            reportStatus("Internal audio capture failed: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            stopCapture();
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void writeCapture(int startId) {
        File out = new File(getFilesDir(), "capture.pcm");
        byte[] buffer = new byte[16384];
        long totalBytes = 0;
        boolean firstBufferReported = false;
        try (FileOutputStream stream = new FileOutputStream(out, false)) {
            while (recording) {
                AudioRecord active;
                synchronized (lock) { active = recorder; }
                if (active == null) break;

                int count = active.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    totalBytes += count;
                    if (!firstBufferReported) {
                        firstBufferReported = true;
                        reportStatus(injectionOutput == null
                            ? "Internal audio captured successfully."
                            : "Internal audio captured; forwarding PCM to the experimental microphone path.");
                    }
                    stream.write(buffer, 0, count);

                    OutputStream pipe = injectionOutput;
                    if (pipe != null) {
                        try {
                            pipe.write(buffer, 0, count);
                            pipe.flush();
                        } catch (IOException pipeError) {
                            closeInjectionPipe();
                            reportStatus("Recording continues; microphone forwarding pipe disconnected.");
                        }
                    }
                } else if (count < 0) {
                    // A stop can unblock read() with an error code; don't report that as a failure.
                    if (!recording) break;
                    throw new IOException("AudioRecord read error " + count);
                }
            }
            stream.flush();
        } catch (Exception e) {
            if (recording) {
                reportStatus("Audio capture stream error: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        } finally {
            if (totalBytes == 0 && recording) {
                reportStatus("Capture is active, but Android supplied no eligible playback audio. "
                    + "The source app may block playback capture.");
            } else if (totalBytes > 0) {
                reportStatus("Recording saved internally (" + totalBytes + " PCM bytes).");
            }
            recording = false;
            closeInjectionPipe();
            AudioRecord active;
            MediaProjection activeProjection;
            synchronized (lock) {
                active = recorder;
                recorder = null;
                activeProjection = projection;
                projection = null;
                if (writerThread == Thread.currentThread()) writerThread = null;
            }
            if (active != null) {
                try { active.stop(); } catch (Exception ignored) {}
                try { active.release(); } catch (Exception ignored) {}
            }
            if (activeProjection != null) {
                try { activeProjection.stop(); } catch (Exception ignored) {}
            }
            stopForeground(true);
            stopSelf(startId);
        }
    }

    private void reportStatus(String message) {
        Intent update = new Intent("com.alxt.SoundPad.CAPTURE_STATUS")
            .setPackage(getPackageName())
            .putExtra("message", message);
        sendBroadcast(update);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel("sp",
                "SoundPad recording", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void closeInjectionPipe() {
        OutputStream output = injectionOutput;
        injectionOutput = null;
        if (output != null) {
            try { output.close(); } catch (Exception ignored) {}
        }
        ParcelFileDescriptor pipe = injectionPipe;
        injectionPipe = null;
        if (pipe != null) {
            try { pipe.close(); } catch (Exception ignored) {}
        }
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
