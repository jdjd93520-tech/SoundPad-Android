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
    private static final String[] RESOLUTIONS = {"720x1360", "700x1360", "900x1600", "540x1080", "1024x768", "900x720", "960x720", "1080x720", "1280x800"};
    private String selectedMode = "stretched";
    private TextView statusText, displayText, modeDescription;
    private Button stretchedButton, centerButton, previewButton, preview30Button;
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
        TextView title = label("Display Control", 29, fg, true);
        LinearLayout.LayoutParams titleLp = params(-1, -2); titleLp.topMargin = dp(5);
        root.addView(title, titleLp);
        TextView subtitle = label("Choose a display mode and logical resolution.", 14, muted, false);
        LinearLayout.LayoutParams subLp = params(-1, -2); subLp.topMargin = dp(6);
        root.addView(subtitle, subLp);

        LinearLayout statusCard = card(panel);
        LinearLayout.LayoutParams cardLp = params(-1, -2); cardLp.topMargin = dp(24);
        root.addView(statusCard, cardLp);
        statusCard.addView(label("CONNECTION STATUS", 11, muted, true));
        statusText = label("Checking Shizuku…", 15, fg, true);
        LinearLayout.LayoutParams statusLp = params(-1, -2); statusLp.topMargin = dp(8);
        statusCard.addView(statusText, statusLp);
        displayText = label("Current display settings: checking…", 13, muted, false);
        LinearLayout.LayoutParams displayLp = params(-1, -2); displayLp.topMargin = dp(8);
        statusCard.addView(displayText, displayLp);

        TextView modeTitle = label("SCALING MODE", 12, muted, true);
        LinearLayout.LayoutParams modeTitleLp = params(-1, -2); modeTitleLp.topMargin = dp(26);
        root.addView(modeTitle, modeTitleLp);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams modesLp = params(-1, dp(58)); modesLp.topMargin = dp(10);
        root.addView(modes, modesLp);
        stretchedButton = modeButton("STRETCHED");
        centerButton = modeButton("CENTER / NATIVE");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1);
        half.rightMargin = dp(6); modes.addView(stretchedButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -1, 1);
        half2.leftMargin = dp(6); modes.addView(centerButton, half2);
        stretchedButton.setOnClickListener(v -> selectMode("stretched"));
        centerButton.setOnClickListener(v -> selectMode("center"));

        LinearLayout descriptionCard = card(panel);
        LinearLayout.LayoutParams descLp = params(-1, -2); descLp.topMargin = dp(12);
        root.addView(descriptionCard, descLp);
        modeDescription = label("", 14, fg, false);
        descriptionCard.addView(modeDescription);
        selectMode("stretched");

        TextView resolutionTitle = label("STRETCHED RESOLUTION", 12, muted, true);
        LinearLayout.LayoutParams resolutionTitleLp = params(-1, -2);
        resolutionTitleLp.topMargin = dp(22);
        root.addView(resolutionTitle, resolutionTitleLp);

        resolutionSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, RESOLUTIONS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        resolutionSpinner.setSelection(0);
        LinearLayout.LayoutParams spinnerLp = params(-1, dp(52));
        spinnerLp.topMargin = dp(8);
        root.addView(resolutionSpinner, spinnerLp);

        previewButton = actionButton("PREVIEW 20 SEC", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams previewLp = params(-1, dp(54));
        previewLp.topMargin = dp(14);
        root.addView(previewButton, previewLp);
        previewButton.setOnClickListener(v -> previewSelectedResolution(20 * 1000L, "20 seconds"));

        preview30Button = actionButton("PREVIEW 30 MIN", accent, Color.WHITE);
        LinearLayout.LayoutParams preview30Lp = params(-1, dp(54));
        preview30Lp.topMargin = dp(10);
        root.addView(preview30Button, preview30Lp);
        preview30Button.setOnClickListener(v -> previewSelectedResolution(30 * 60 * 1000L, "30 minutes"));

        Button restore = actionButton("RESTORE ORIGINAL DISPLAY", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams restoreLp = params(-1, dp(54)); restoreLp.topMargin = dp(10);
        root.addView(restore, restoreLp);
        restore.setOnClickListener(v -> confirmRestore());

        TextView safety = label("PREVIEW 20 SEC and PREVIEW 30 MIN temporarily apply the selected resolution, then attempt to restore your saved settings automatically. There is no permanent apply option. You can restore sooner with RESTORE ORIGINAL DISPLAY. Keep Shizuku running and available for automatic restore.", 12, muted, false);
        LinearLayout.LayoutParams safetyLp = params(-1, -2); safetyLp.topMargin = dp(20);
        root.addView(safety, safetyLp);

        TextView note = label("The nine presets are logical display sizes. Landscape previews temporarily lock Android to landscape and restore your previous rotation setting afterward. Android normally remaps touch coordinates when the display size changes, but some apps or device firmware may handle scaling differently. This app cannot guarantee identical touch behavior on every Motorola model or force the panel's hardware scaler to stretch instead of letterbox.", 12, muted, false);
        LinearLayout.LayoutParams noteLp = params(-1, -2); noteLp.topMargin = dp(12);
        root.addView(note, noteLp);
        setContentView(scroll);
    }

    private void selectMode(String mode) {
        selectedMode = mode;
        boolean stretched = "stretched".equals(mode);
        stretchedButton.setBackgroundColor(stretched ? Color.rgb(48, 113, 211) : Color.rgb(40, 52, 72));
        centerButton.setBackgroundColor(stretched ? Color.rgb(40, 52, 72) : Color.rgb(48, 113, 211));
        if (resolutionSpinner != null) resolutionSpinner.setEnabled(stretched);
        if (previewButton != null) previewButton.setEnabled(stretched);
        if (preview30Button != null) preview30Button.setEnabled(stretched);
        modeDescription.setText(stretched
                ? "STRETCHED applies the selected logical resolution. Choose one of the presets below and use a timed preview. The display automatically attempts to return to its saved settings when the preview ends."
                : "CENTER / NATIVE restores the saved original resolution, DPI, and scaling settings. Android does not provide a universal system-wide GPU-style centered mode.");
    }

    private String selectedResolution() {
        Object value = resolutionSpinner.getSelectedItem();
        return value == null ? RESOLUTIONS[0] : value.toString();
    }

    private void setActionsEnabled(boolean enabled) {
        previewButton.setEnabled(enabled);
        preview30Button.setEnabled(enabled && "stretched".equals(selectedMode));
        resolutionSpinner.setEnabled(enabled && "stretched".equals(selectedMode));
    }

    private void previewSelectedResolution(long durationMillis, String durationLabel) {
        if (!"stretched".equals(selectedMode)) {
            setStatus("Select STRETCHED mode to preview a resolution.");
            return;
        }
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