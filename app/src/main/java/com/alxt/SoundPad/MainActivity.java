package com.alxt.SoundPad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.util.DisplayMetrics;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;
import java.util.LinkedHashSet;

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
        String[] resolutions = buildResolutionOptions();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        resolutionSpinner.setSelection(resolutions.length - 1);
        LinearLayout.LayoutParams spinnerLp = params(-1, dp(54));
        spinnerLp.topMargin = dp(8);
        root.addView(resolutionSpinner, spinnerLp);
        applyButton = actionButton("APPLY FULL-PANEL SCALE", accent, Color.WHITE);
        LinearLayout.LayoutParams stretchLp = params(-1, dp(58));
        stretchLp.topMargin = dp(14);
        root.addView(applyButton, stretchLp);
        applyButton.setOnClickListener(v -> { String selected = selectedResolution(); if ("200x1080".equals(selected)) previewSelectedResolution(20 * 1000L, "20 seconds (experimental safety preview)"); else previewSelectedResolution(30 * 60 * 1000L, "30 minutes"); });
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
        Button guideButton = actionButton("TOUCH & FULL-SCREEN GUIDE", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams guideLp = params(-1, dp(50)); guideLp.topMargin = dp(10);
        root.addView(guideButton, guideLp);
        guideButton.setOnClickListener(v -> showDisplayGuide());
        restoreButton = actionButton("RESTORE ORIGINAL DISPLAY", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams restoreLp = params(-1, dp(54)); restoreLp.topMargin = dp(10);
        root.addView(restoreButton, restoreLp);
        restoreButton.setOnClickListener(v -> confirmRestore());
        TextView safety = label("The selected resolution is temporary and automatically restores after the preview. The experimental 200x1080 preset is limited to a 20-second safety preview. Keep Shizuku running so the restore can complete. If the display or touch layout looks wrong, use RESTORE ORIGINAL DISPLAY or wait for the timer.", 12, muted, false);
        LinearLayout.LayoutParams safetyLp = params(-1, -2); safetyLp.topMargin = dp(20);
        root.addView(safety, safetyLp);
        TextView note = label("Resolution values are sent in Android's device natural-orientation order, then the display is rotated to landscape. The app keeps your original DPI unchanged to avoid unnecessary UI and touch-size changes. Android still controls touch mapping; some OEMs or apps may handle custom sizes differently.", 12, muted, false);
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

    private void showDisplayGuide() {
        new AlertDialog.Builder(this)
                .setTitle("Touch & full-screen guide")
                .setMessage(
                        "ANDROID GAMES\n" +
                        "1. Choose a landscape ratio. The app sends the size in device natural-orientation order, then locks landscape.\n" +
                        "2. Open Android Settings and look for Display > Full-screen apps, App aspect ratio, or Camera cutout. If your phone offers a per-app full-screen option, enable it for the app you want. Menu names vary by brand; this app does not change hidden OEM settings.\n" +
                        "3. Keep the original DPI. Android normally maps touch using its display configuration, but some games or manufacturer builds may not handle custom sizes correctly. Test the corners and buttons before using a long preview. Restore immediately if touch is offset.\n" +
                        "4. If reverse landscape behaves differently, rotate the phone 180 degrees and test again. This is only a compatibility check, not a guaranteed fix.\n\n" +
                        "MOONLIGHT / PC STREAMING\n" +
                        "For streamed PC games, set the host PC to the desired aspect ratio and use Moonlight's own Stretch video to full screen option if available. This app cannot change the host resolution or Moonlight's touch translation.\n\n" +
                        "GAME DEVELOPMENT\n" +
                        "For apps you develop, render the game view to a RenderTexture and stretch that view to the display while keeping touch/UI controls in a separate screen-space layer. This is an app-development technique, not a system setting.\n\n" +
                        "IMPORTANT\n" +
                        "Standard Android resolution commands cannot guarantee non-uniform GPU stretching or remove bars from every app. If an app enforces its own aspect ratio, Android may still letterbox it."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private String[] buildResolutionOptions() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean metricsAreRotated = rotation == 1 || rotation == 3;
        int naturalWidth = metricsAreRotated ? metrics.heightPixels : metrics.widthPixels;
        int naturalHeight = metricsAreRotated ? metrics.widthPixels : metrics.heightPixels;
        int shortEdge = Math.min(naturalWidth, naturalHeight);
        int longEdge = Math.max(naturalWidth, naturalHeight);
        if (longEdge < 320 || shortEdge < 320) {
            shortEdge = 720;
            longEdge = 1600;
            naturalWidth = shortEdge;
            naturalHeight = longEdge;
        }
        boolean naturalPortrait = naturalWidth < naturalHeight;
        LinkedHashSet<String> options = new LinkedHashSet<>();
        double[] ratios = {4.0 / 3.0, 16.0 / 9.0, 18.0 / 9.0, 19.5 / 9.0, 20.0 / 9.0};
        String[] names = {"4:3", "16:9", "18:9", "19.5:9", "20:9"};
        for (int i = 0; i < ratios.length; i++) {
            int longSide = (int) Math.round(shortEdge * ratios[i]);
            if (longSide <= longEdge) {
                int logicalWidth = naturalPortrait ? shortEdge : longSide;
                int logicalHeight = naturalPortrait ? longSide : shortEdge;
                options.add("Landscape " + names[i] + " — " + logicalWidth + "x" + logicalHeight);
            }
        }
        if (naturalPortrait) options.add("Experimental 200x1080 — 200x1080");
        else options.add("Experimental 200x1080 — 1080x200");
        options.add("Native aspect — " + naturalWidth + "x" + naturalHeight);
        return options.toArray(new String[0]);
    }

    private String selectedResolution() {
        Object selected = resolutionSpinner == null ? null : resolutionSpinner.getSelectedItem();
        if (selected == null) throw new IllegalStateException("Choose a resolution first.");
        String value = selected.toString();
        int separator = value.lastIndexOf("—");
        return (separator >= 0 ? value.substring(separator + 1) : value).trim();
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