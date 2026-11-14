package com.alxt.SoundPad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 42;
    private TextView statusText, displayText;
    private Button applyButton, previewButton, forceLandscapeButton, restoreButton;
    private Spinner resolutionSpinner;
    private boolean permissionListenerRegistered;
    private boolean permissionRequestAttemptedThisSession;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == REQUEST_SHIZUKU) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        refreshStatus();
                        Toast.makeText(this, "Shizuku permission granted", Toast.LENGTH_SHORT).show();
                    } else setStatus("Shizuku permission was not granted. No display changes were made.");
                }
            };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (RuntimeException e) {
            TextView fallback = new TextView(this);
            fallback.setTextColor(Color.BLACK);
            fallback.setTextSize(16);
            fallback.setPadding(32, 32, 32, 32);
            fallback.setText("Display Launcher could not create its screen.\n\n" + safeMessage(e));
            setContentView(fallback);
            return;
        }
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
            permissionListenerRegistered = true;
        } catch (RuntimeException e) {
            setStatus("Shizuku initialization problem: " + safeMessage(e));
        }
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        requestShizukuPermissionOnFirstResume();
    }

    private void requestShizukuPermissionOnFirstResume() {
        if (permissionRequestAttemptedThisSession) return;
        permissionRequestAttemptedThisSession = true;
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                    && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_SHIZUKU);
            }
        } catch (RuntimeException e) {
            setStatus("Could not contact Shizuku: " + safeMessage(e));
        }
    }

    @Override protected void onDestroy() {
        if (permissionListenerRegistered) {
            try { Shizuku.removeRequestPermissionResultListener(permissionListener); }
            catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    private void buildUi() {
        int bg = Color.rgb(12, 17, 29), panel = Color.rgb(24, 33, 51);
        int fg = Color.rgb(240, 244, 252), muted = Color.rgb(166, 180, 203);
        int accent = Color.rgb(84, 151, 255);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(bg);
        scroll.addView(root);
        root.addView(label("DISPLAY TOOLS", 12, accent, true));
        TextView title = label("Full-Screen Resolution", 27, fg, true);
        LinearLayout.LayoutParams titleLp = params(-1, -2); titleLp.topMargin = dp(5);
        root.addView(title, titleLp);
        TextView subtitle = label("Choose a resolution and preview it with Android display scaling enabled.", 14, muted, false);
        LinearLayout.LayoutParams subLp = params(-1, -2); subLp.topMargin = dp(6);
        root.addView(subtitle, subLp);
        LinearLayout statusCard = card(panel);
        LinearLayout.LayoutParams cardLp = params(-1, -2); cardLp.topMargin = dp(22);
        root.addView(statusCard, cardLp);
        statusCard.addView(label("CONNECTION STATUS", 11, muted, true));
        statusText = label("Checking Shizuku…", 15, fg, true);
        LinearLayout.LayoutParams statusLp = params(-1, -2); statusLp.topMargin = dp(8);
        statusCard.addView(statusText, statusLp);
        displayText = label("Current display settings: checking…", 13, muted, false);
        LinearLayout.LayoutParams displayLp = params(-1, -2); displayLp.topMargin = dp(8);
        statusCard.addView(displayText, displayLp);
        TextView resolutionTitle = label("RESOLUTION", 12, muted, true);
        LinearLayout.LayoutParams resolutionTitleLp = params(-1, -2);
        resolutionTitleLp.topMargin = dp(24);
        root.addView(label("FULL-PANEL / STRETCHED RESOLUTION", 12, muted, true), resolutionTitleLp);
        resolutionSpinner = new Spinner(this);
        String[] resolutions = {"1600x718", "1440x646", "1280x574", "1024x460", "960x430", "800x360",
                "1024x768", "1280x960", "1440x1080", "1600x1200", "1920x1440",
                "800x600", "960x720", "800x640", "1000x800", "1280x1024", "1600x1280",
                "1200x800", "1440x960", "1620x1080", "1920x1280", "1280x800", "1600x1000"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        resolutionSpinner.setSelection(3);
        LinearLayout.LayoutParams spinnerLp = params(-1, dp(54));
        spinnerLp.topMargin = dp(8);
        root.addView(resolutionSpinner, spinnerLp);
        applyButton = actionButton("APPLY FULL-PANEL SCALE", accent, Color.WHITE);
        LinearLayout.LayoutParams stretchLp = params(-1, dp(58));
        stretchLp.topMargin = dp(14);
        root.addView(applyButton, stretchLp);
        applyButton.setOnClickListener(v -> previewSelectedResolution(30 * 60 * 1000L, "30 minutes"));
        previewButton = actionButton("QUICK PREVIEW (20 SEC)", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams previewLp = params(-1, dp(54));
        previewLp.topMargin = dp(10);
        root.addView(previewButton, previewLp);
        previewButton.setOnClickListener(v -> previewSelectedResolution(20 * 1000L, "20 seconds"));
        forceLandscapeButton = actionButton("FORCE LANDSCAPE (30 MIN)", Color.rgb(37, 118, 91), Color.WHITE);
        LinearLayout.LayoutParams forceLp = params(-1, dp(54));
        forceLp.topMargin = dp(10);
        root.addView(forceLandscapeButton, forceLp);
        forceLandscapeButton.setOnClickListener(v -> forceLandscapeNow());
        restoreButton = actionButton("RESTORE ORIGINAL DISPLAY", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams restoreLp = params(-1, dp(54)); restoreLp.topMargin = dp(10);
        root.addView(restoreButton, restoreLp);
        restoreButton.setOnClickListener(v -> confirmRestore());
        TextView safety = label("The selected resolution is temporary and automatically restores after the preview. Keep Shizuku running so the restore can complete. If the display or touch layout looks wrong, use RESTORE ORIGINAL DISPLAY or wait for the timer.", 12, muted, false);
        LinearLayout.LayoutParams safetyLp = params(-1, -2); safetyLp.topMargin = dp(20);
        root.addView(safety, safetyLp);
        TextView note = label("The first six choices are wide landscape resolutions matched to an approximately 1604x720 phone panel; choose one of these to reduce side bars. Android Auto scaling is enabled. This is the closest standard Android system-level full-panel mode, not a universal GPU warp: some games or Motorola firmware may still letterbox or keep their own aspect ratio. The 4:3 choices are included for compatibility and can show bars.", 12, muted, false);
        LinearLayout.LayoutParams noteLp = params(-1, -2); noteLp.topMargin = dp(12);
        root.addView(note, noteLp);
        setContentView(scroll);
    }

    private void previewSelectedResolution(long durationMillis, String durationLabel) {
        if (!hasShizukuPermission()) return;
        final String resolution = selectedResolution();
        new AlertDialog.Builder(this).setTitle("Preview " + resolution + " for " + durationLabel + "?")
                .setMessage("This temporarily changes the logical display resolution for " + durationLabel
                        + ", then attempts to restore your saved original settings. For a 30-minute preview, keep Shizuku running and available. If the picture or touch layout looks wrong, use RESTORE ORIGINAL DISPLAY or wait for the automatic restore.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("PREVIEW", (d, w) -> runPreview(resolution, durationMillis, durationLabel)).show();
    }

    private void runPreview(String resolution, long durationMillis, String durationLabel) {
        setActionsEnabled(false);
        new Thread(() -> {
            try {
                DisplayController.previewResolution(this, resolution, durationMillis);
                runOnUiThread(() -> {
                    setActionsEnabled(true);
                    setStatus("Previewing " + resolution + ". Automatic restore is scheduled in " + durationLabel + ".");
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setActionsEnabled(true); setStatus("Preview failed: " + safeMessage(e)); });
            }
        }).start();
    }

    private void forceLandscapeNow() {
        if (!hasShizukuPermission()) return;
        new AlertDialog.Builder(this).setTitle("Force landscape for 30 minutes?")
                .setMessage("This locks Android's user rotation to landscape and schedules restoration of your previous display and rotation settings after 30 minutes. Some games can still override the lock.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("FORCE LANDSCAPE", (d, w) -> {
                    forceLandscapeButton.setEnabled(false);
                    new Thread(() -> {
                        try {
                            DisplayController.forceLandscape(this);
                            runOnUiThread(() -> {
                                forceLandscapeButton.setEnabled(true);
                                setStatus("Landscape rotation lock enabled. Restore is scheduled in 30 minutes.");
                                refreshStatus();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                forceLandscapeButton.setEnabled(true);
                                setStatus("Could not force landscape: " + safeMessage(e));
                            });
                        }
                    }).start();
                }).show();
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this).setTitle("Restore original display?")
                .setMessage("This restores the resolution, DPI, and scaling mode saved before the preview or apply.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RESTORE", (d, w) -> restoreNow()).show();
    }

    private void restoreNow() {
        if (!hasShizukuPermission()) return;
        setActionsEnabled(false);
        new Thread(() -> {
            try {
                DisplayController.restoreOriginal(this);
                runOnUiThread(() -> {
                    setActionsEnabled(true);
                    setStatus("Original display settings restored and verified.");
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setActionsEnabled(true); setStatus("Restore failed: " + safeMessage(e)); });
            }
        }).start();
    }

    private boolean hasShizukuPermission() {
        if (!Shizuku.pingBinder()) { setStatus("Shizuku is not running. Start Shizuku, then try again."); return false; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_SHIZUKU);
            setStatus("Waiting for Shizuku permission. Approve the request, then try again.");
            return false;
        }
        return true;
    }

    private void refreshStatus() {
        new Thread(() -> {
            String status, display;
            try {
                if (!Shizuku.pingBinder()) {
                    status = "Shizuku not connected";
                    display = "Start Shizuku to read or change system display settings.";
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    status = "Shizuku permission required";
                    display = "Grant this app Shizuku access to inspect the current display.";
                } else {
                    status = "Shizuku connected and authorized";
                    display = DisplayController.readDisplaySummary();
                }
            } catch (Exception e) { status = "Unable to read display settings"; display = safeMessage(e); }
            final String s = status, d = display;
            runOnUiThread(() -> { statusText.setText(s); displayText.setText(d); });
        }).start();
    }

    private String selectedResolution() {
        Object selected = resolutionSpinner == null ? null : resolutionSpinner.getSelectedItem();
        if (selected == null) throw new IllegalStateException("Choose a resolution first.");
        return selected.toString();
    }

    private void setActionsEnabled(boolean enabled) {
        if (resolutionSpinner != null) resolutionSpinner.setEnabled(enabled);
        if (applyButton != null) applyButton.setEnabled(enabled);
        if (previewButton != null) previewButton.setEnabled(enabled);
        if (forceLandscapeButton != null) forceLandscapeButton.setEnabled(enabled);
        if (restoreButton != null) restoreButton.setEnabled(enabled);
    }

    private void setStatus(String value) { if (statusText != null) statusText.setText(value); }
    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private LinearLayout card(int color) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(16), dp(16), dp(16)); v.setBackgroundColor(color); return v;
    }
    private Button modeButton(String text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(13); b.setTextColor(Color.WHITE); b.setAllCaps(false); return b;
    }
    private Button actionButton(String text, int background, int foreground) {
        Button b = new Button(this); b.setText(text); b.setTextSize(13); b.setTextColor(foreground);
        b.setAllCaps(false); b.setBackgroundColor(background); return b;
    }
    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}