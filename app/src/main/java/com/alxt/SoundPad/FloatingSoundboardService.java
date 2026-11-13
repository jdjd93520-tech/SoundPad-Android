package com.alxt.SoundPad;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class FloatingSoundboardService extends Service {
    public static final String START = "com.alxt.SoundPad.FLOAT_START";
    public static final String STOP = "com.alxt.SoundPad.FLOAT_STOP";
    public static final String EXTRA_URI = "sound_uri";
    private WindowManager windowManager;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    private Uri soundUri;
    private boolean locked = true;
    private int startX, startY, touchX, touchY;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (STOP.equals(intent.getAction())) { removePanel(); stopSelf(); return START_NOT_STICKY; }
        if (intent.hasExtra(EXTRA_URI)) {
            String value = intent.getStringExtra(EXTRA_URI);
            if (value != null) soundUri = Uri.parse(value);
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow SoundPad to display over other apps first.", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (panel == null) showPanel();
        return START_STICKY;
    }

    private void showPanel() {
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(8, 8, 8, 8);
        panel.setBackgroundColor(0xee20242b);
        Button play = button("PLAY", v -> playSound());
        Button stop = button("STOP", v -> {
            startService(new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.STOP));
        });
        Button lock = button("UNLOCK", v -> {
            locked = !locked;
            ((Button)v).setText(locked ? "UNLOCK" : "LOCK");
            Toast.makeText(this, locked ? "Floating panel locked" : "Floating panel unlocked — drag to move", Toast.LENGTH_SHORT).show();
        });
        Button close = button("X", v -> { removePanel(); stopSelf(); });
        panel.addView(play); panel.addView(stop); panel.addView(lock); panel.addView(close);
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 24; params.y = 180;
        panel.setOnTouchListener((view, event) -> {
            if (locked) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startX = params.x; startY = params.y; touchX = (int)event.getRawX(); touchY = (int)event.getRawY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                params.x = startX + (int)event.getRawX() - touchX;
                params.y = startY + (int)event.getRawY() - touchY;
                try { windowManager.updateViewLayout(panel, params); } catch (Exception ignored) {}
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP;
        });
        try { windowManager.addView(panel, params); }
        catch (Exception e) { panel = null; stopSelf(); }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setMinWidth(48);
        b.setOnClickListener(listener);
        return b;
    }

    private void playSound() {
        if (soundUri == null) {
            Toast.makeText(this, "Choose a sound in SoundPad first, then reopen the floating panel.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent play = new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.PLAY).setData(soundUri);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(play); else startService(play);
    }

    private void removePanel() {
        if (panel != null && windowManager != null) {
            try { windowManager.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
    }

    @Override public void onDestroy() { removePanel(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
