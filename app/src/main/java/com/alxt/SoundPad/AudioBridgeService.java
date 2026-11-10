package com.alxt.SoundPad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

public class AudioBridgeService extends Service {
    public static final String PLAY = "com.alxt.SoundPad.AUDIO_PLAY";
    public static final String PAUSE = "com.alxt.SoundPad.AUDIO_PAUSE";
    public static final String RESUME = "com.alxt.SoundPad.AUDIO_RESUME";
    public static final String STOP = "com.alxt.SoundPad.AUDIO_STOP";
    public static final String ROUTE_CALL_AUDIO = "com.alxt.SoundPad.AUDIO_ROUTE_CALL";
    public static final String SET_OUTPUT_DEVICE = "com.alxt.SoundPad.AUDIO_SET_OUTPUT_DEVICE";
    public static final String EXTRA_OUTPUT_DEVICE_ID = "output_device_id";
    private int preferredOutputDeviceId = 0;
    private android.media.AudioManager audioManager;
    private boolean callAudioRoute = false;
    private static final String CHANNEL = "soundpad_audio_bridge";
    private static final int NOTIFICATION_ID = 7301;
    private MediaPlayer player;

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "SoundPad background audio", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (SET_OUTPUT_DEVICE.equals(action)) {
            preferredOutputDeviceId = intent.getIntExtra(EXTRA_OUTPUT_DEVICE_ID, 0);
            applyPreferredOutput();
            showNotification(preferredOutputDeviceId == 0 ? "Using system-default audio output" : "Requested selected audio output");
            return START_NOT_STICKY;
        }
        if (ROUTE_CALL_AUDIO.equals(action)) {
            callAudioRoute = true;
            if (audioManager != null) audioManager.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            showNotification("Call-audio routing requested; microphone injection is not provided");
            return START_NOT_STICKY;
        }
        if (STOP.equals(action)) {
            releasePlayer();
            resetAudioRoute();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (PAUSE.equals(action)) {
            if (player != null && player.isPlaying()) player.pause();
            showNotification("Paused");
            return START_NOT_STICKY;
        }
        if (RESUME.equals(action)) {
            if (player != null) {
                try { player.start(); showNotification("Playing"); }
                catch (IllegalStateException e) { showNotification("Choose the audio file again"); }
            }
            return START_NOT_STICKY;
        }
        if (PLAY.equals(action) && intent.getData() != null) {
            startForeground(NOTIFICATION_ID, buildNotification("Preparing audio"));
            releasePlayer();
            preferredOutputDeviceId = intent.getIntExtra(EXTRA_OUTPUT_DEVICE_ID, preferredOutputDeviceId);
            Uri uri = intent.getData();
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(callAudioRoute ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                player.setDataSource(this, uri);
                applyPreferredOutput();
                player.setOnPreparedListener(mp -> {
                    mp.start();
                    showNotification("Playing through current phone/Bluetooth output");
                });
                player.setOnCompletionListener(mp -> showNotification("Playback finished"));
                player.setOnErrorListener((mp, what, extra) -> {
                    showNotification("Playback error — select another file");
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                releasePlayer();
                showNotification("Could not open this audio file");
            }
        }
        return START_NOT_STICKY;
    }

    private void applyPreferredOutput() {
        if (Build.VERSION.SDK_INT >= 23 && player != null) {
            if (preferredOutputDeviceId == 0) {
                player.setPreferredDevice(null);
                return;
            }
            android.media.AudioManager manager = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
            if (manager == null) return;
            for (android.media.AudioDeviceInfo device : manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getId() == preferredOutputDeviceId) {
                    boolean applied = player.setPreferredDevice(device);
                    if (!applied) showNotification("Android rejected this output route; using system default");
                    return;
                }
            }
            showNotification("Selected output is no longer connected; using system default");
        }
    }

    private Notification buildNotification(String state) {
        Intent pause = new Intent(this, AudioBridgeService.class).setAction(PAUSE);
        Intent stop = new Intent(this, AudioBridgeService.class).setAction(STOP);
        PendingIntent pausePending = PendingIntent.getService(this, 1, pause, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SoundPad Audio Bridge")
            .setContentText(state)
            .setOngoing("Playing".equals(state) || "Preparing audio".equals(state))
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending);
        return builder.build();
    }

    private void showNotification(String state) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(state));
    }

    private void resetAudioRoute() {
        if (audioManager != null && callAudioRoute) {
            try { audioManager.setMode(android.media.AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
        }
        callAudioRoute = false;
    }

    private void releasePlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override public void onDestroy() { releasePlayer(); resetAudioRoute(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
