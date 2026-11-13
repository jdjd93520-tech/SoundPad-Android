package com.alxt.SoundPad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public class DisplayRestoreService extends Service {
    public static final String ACTION_APPLY = "com.alxt.SoundPad.APPLY_STRETCH";
    public static final String ACTION_RESTORE = "com.alxt.SoundPad.RESTORE_DISPLAY";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    private static final String PREFS = "safe_display_restore";
    private static final String ACTIVE = "active";
    private static final String ORIGINAL_SIZE = "original_size";
    private static final String ORIGINAL_DENSITY = "original_density";
    private static final String DEADLINE = "deadline";
    private static final long TEN_MINUTES = 10 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::restore;
    private boolean receiverRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) restore();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        registerScreenOff();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(4107, notification("Protecting the original display settings"));
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (ACTION_APPLY.equals(action)) {
            int w = intent.getIntExtra(EXTRA_WIDTH, 0);
            int h = intent.getIntExtra(EXTRA_HEIGHT, 0);
            new Thread(() -> apply(w, h), "safe-display-apply").start();
        } else if (ACTION_RESTORE.equals(action)) {
            new Thread(this::restore, "safe-display-restore").start();
        } else if (getPreferences().getBoolean(ACTIVE, false)) {
            long deadline = getPreferences().getLong(DEADLINE, 0L);
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) new Thread(this::restore, "safe-display-expired").start();
            else handler.postDelayed(timeout, remaining);
        } else stopSelf();
        return START_STICKY;
    }

    private void apply(int width, int height) {
        if (width < 200 || height < 200) { finishWith("Invalid resolution values"); return; }
        try {
            requireShizuku();
            android.content.SharedPreferences prefs = getPreferences();
            if (!prefs.getBoolean(ACTIVE, false)) {
                String sizeOutput = shell("wm size");
                String densityOutput = shell("wm density");
                Matcher size = Pattern.compile("Override size:\\s*(\\d+x\\d+)").matcher(sizeOutput);
                Matcher density = Pattern.compile("Override density:\\s*(\\d+)").matcher(densityOutput);
                prefs.edit()
                    .putString(ORIGINAL_SIZE, size.find() ? size.group(1) : "")
                    .putString(ORIGINAL_DENSITY, density.find() ? density.group(1) : "")
                    .putBoolean(ACTIVE, true)
                    .putLong(DEADLINE, System.currentTimeMillis() + TEN_MINUTES)
                    .apply();
            } else {
                prefs.edit().putLong(DEADLINE, System.currentTimeMillis() + TEN_MINUTES).apply();
            }
            shell("wm size " + width + "x" + height);
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, TEN_MINUTES);
            updateNotification("Stretch active — auto-reset in 10 minutes");
        } catch (Exception e) {
            finishWith("Stretch failed: " + e.getMessage());
        }
    }

    private void restore() {
        handler.removeCallbacks(timeout);
        if (!getPreferences().getBoolean(ACTIVE, false)) {
            stopSafely();
            return;
        }
        try {
            requireShizuku();
            android.content.SharedPreferences prefs = getPreferences();
            String size = prefs.getString(ORIGINAL_SIZE, "");
            String density = prefs.getString(ORIGINAL_DENSITY, "");
            shell(size == null || size.isEmpty() ? "wm size reset" : "wm size " + size);
            shell(density == null || density.isEmpty() ? "wm density reset" : "wm density " + density);
            prefs.edit().clear().apply();
            updateNotification("Original display settings restored");
            stopSafely();
        } catch (Exception e) {
            updateNotification("Restore needs Shizuku: " + e.getMessage());
            // Keep the saved original values so the user can retry manually.
            handler.postDelayed(() -> {
                if (getPreferences().getBoolean(ACTIVE, false)) restore();
            }, 15000L);
        }
    }

    private void requireShizuku() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Start Shizuku and grant this app permission");
    }

    private String shell(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Process process = (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            while ((line = errors.readLine()) != null) output.append(line).append('\n');
        }
        int result = process.waitFor();
        if (result != 0) throw new IllegalStateException("Command failed (" + result + "): " + output.toString().trim());
        return output.toString();
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
    private void registerScreenOff() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, filter);
        receiverRegistered = true;
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("display_restore", "Display safety reset", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    private Notification notification(String message) {
        if (Build.VERSION.SDK_INT >= 26)
            return new Notification.Builder(this, "display_restore").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("PUBG Stretch safety").setContentText(message).setOngoing(true).build();
        return new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("PUBG Stretch safety").setContentText(message).setOngoing(true).build();
    }
    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(4107, notification(message));
    }
    private void finishWith(String message) {
        updateNotification(message);
        stopSafely();
    }
    private void stopSafely() {
        handler.removeCallbacks(timeout);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (getPreferences().getBoolean(ACTIVE, false)) new Thread(this::restore, "display-task-removed-reset").start();
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(timeout);
        if (receiverRegistered) {
            try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}