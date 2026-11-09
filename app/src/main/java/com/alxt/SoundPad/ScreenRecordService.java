package com.alxt.SoundPad;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecordService extends Service {
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private WindowManager windowManager;
    private TextView hud;
    private File outputFile;
    private boolean started;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("STOP".equals(intent.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (!"START".equals(intent.getAction()) || started) return START_NOT_STICKY;

        createChannel();
        Notification notification = new Notification.Builder(this, "soundpad_screen")
            .setContentTitle("SoundPad screen recording")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(3, notification);

        try {
            int code = intent.getIntExtra("code", 0);
            Intent data = (Intent)intent.getParcelableExtra("data");
            int requestedFps = intent.getIntExtra("fps", 60);
            boolean showHud = intent.getBooleanExtra("hud", false);
            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("Screen capture permission was not granted");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopRecording(); }
            }, new Handler(Looper.getMainLooper()));

            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager)getSystemService(WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.Display display = getDisplay();
                if (display == null) display = wm.getDefaultDisplay();
                display.getRealMetrics(metrics);
            } else {
                wm.getDefaultDisplay().getRealMetrics(metrics);
            }
            int width = metrics.widthPixels & ~1;
            int height = metrics.heightPixels & ~1;
            int density = metrics.densityDpi;
            File folder = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SoundPad");
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Could not create recording folder");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            outputFile = new File(folder, "Screen_" + stamp + ".mp4");

            try {
                prepareRecorder(width, height, requestedFps);
            } catch (Exception firstError) {
                if (requestedFps != 120) throw firstError;
                releaseRecorder();
                prepareRecorder(width, height, 60);
            }
            virtualDisplay = projection.createVirtualDisplay("SoundPadScreen",
                width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);
            recorder.start();
            started = true;
            if (showHud && Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(this)) {
                showStatusHud(requestedFps);
            }
        } catch (Exception e) {
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    private void prepareRecorder(int width, int height, int fps) throws Exception {
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(fps);
        recorder.setVideoEncodingBitRate(fps >= 120 ? 24000000 : 12000000);
        recorder.setOutputFile(outputFile.getAbsolutePath());
        recorder.prepare();
    }

    private void showStatusHud(int fps) {
        try {
            windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
            hud = new TextView(this);
            hud.setText("● SoundPad REC  •  " + fps + " FPS target");
            hud.setTextColor(0xffffffff);
            hud.setTextSize(13);
            hud.setPadding(18, 10, 18, 10);
            hud.setBackgroundColor(0xcc202020);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 48;
            windowManager.addView(hud, lp);
        } catch (Exception ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("soundpad_screen",
                "SoundPad screen recording", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void stopRecording() {
        if (!started && projection == null && recorder == null) {
            stopForeground(true);
            stopSelf();
            return;
        }
        started = false;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            releaseRecorder();
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null;
        }
        if (hud != null && windowManager != null) {
            try { windowManager.removeView(hud); } catch (Exception ignored) { }
            hud = null;
        }
        stopForeground(true);
        stopSelf();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    @Override public void onDestroy() {
        if (started || projection != null || recorder != null) stopRecording();
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
