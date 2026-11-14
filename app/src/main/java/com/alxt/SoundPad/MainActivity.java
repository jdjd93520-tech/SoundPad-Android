package com.alxt.SoundPad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 42;
    private String selectedMode = "stretched";
    private TextView statusText, displayText, modeDescription;
    private Button stretchedButton, centerButton, applyButton;
    private boolean permissionListenerRegistered;

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

    @Override protected void onResume() { super.onResume(); refreshStatus(); }

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
        TextView subtitle = label("Choose how Android scales the screen.", 14, muted, false);
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
        centerButton = modeButton("CENTER");
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

        applyButton = actionButton("APPLY SELECTED MODE", accent, Color.WHITE);
        LinearLayout.LayoutParams applyLp = params(-1, dp(54)); applyLp.topMargin = dp(22);
        root.addView(applyButton, applyLp);
        applyButton.setOnClickListener(v -> confirmApply());

        Button restore = actionButton("RESTORE ORIGINAL DISPLAY", Color.rgb(48, 62, 84), fg);
        LinearLayout.LayoutParams restoreLp = params(-1, dp(54)); restoreLp.topMargin = dp(10);
        root.addView(restore, restoreLp);
        restore.setOnClickListener(v -> confirmRestore());

        TextView safety = label("SAFETY: Original resolution, DPI, and scaling mode are saved before changes. The app schedules an automatic restore after 10 minutes and attempts a restore when the screen turns off or after reboot. Shizuku must be running and authorized for these system commands to work.", 12, muted, false);
        LinearLayout.LayoutParams safetyLp = params(-1, -2); safetyLp.topMargin = dp(20);
        root.addView(safety, safetyLp);

        TextView note = label("Mode behavior depends on the Android device's display-scaling implementation. If your device does not support the scaling command, the app will show the command error instead of claiming it worked.", 12, muted, false);
        LinearLayout.LayoutParams noteLp = params(-1, -2); noteLp.topMargin = dp(12);
        root.addView(note, noteLp);
        setContentView(scroll);
    }

    private void selectMode(String mode) {
        selectedMode = mode;
        boolean stretched = "stretched".equals(mode);
        stretchedButton.setBackgroundColor(stretched ? Color.rgb(48, 113, 211) : Color.rgb(40, 52, 72));
        centerButton.setBackgroundColor(stretched ? Color.rgb(40, 52, 72) : Color.rgb(48, 113, 211));
        modeDescription.setText(stretched
                ? "STRETCHED uses Android's automatic display scaling mode. The device decides how logical display content is fitted to the physical panel."
                : "CENTER disables Android display scaling. Content is rendered without automatic fit-to-panel scaling; the exact result depends on your device.");
    }

    private void confirmApply() {
        new AlertDialog.Builder(this).setTitle("Apply " + selectedMode.toUpperCase() + " mode?")
                .setMessage("Your current display settings will be saved first. A safety restore is scheduled for 10 minutes. Screen-off and reboot restore attempts also require Shizuku to be available.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY", (d, w) -> applySelectedMode()).show();
    }

    private void applySelectedMode() {
        if (!hasShizukuPermission()) return;
        applyButton.setEnabled(false);
        new Thread(() -> {
            try {
                DisplayController.applyMode(this, selectedMode);
                runOnUiThread(() -> {
                    applyButton.setEnabled(true);
                    setStatus(selectedMode.toUpperCase() + " mode applied and verified. Automatic restore is scheduled for 10 minutes.");
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { applyButton.setEnabled(true); setStatus("Apply failed: " + safeMessage(e)); });
            }
        }).start();
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this).setTitle("Restore original display?")
                .setMessage("This restores the resolution, DPI, and scaling mode saved before the last change.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RESTORE", (d, w) -> restoreNow()).show();
    }

    private void restoreNow() {
        if (!hasShizukuPermission()) return;
        new Thread(() -> {
            try {
                DisplayController.restoreOriginal(this);
                runOnUiThread(() -> { setStatus("Original display settings restored and verified."); refreshStatus(); });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Restore failed: " + safeMessage(e)));
            }
        }).start();
    }

    private boolean hasShizukuPermission() {
        if (!Shizuku.pingBinder()) { setStatus("Shizuku is not running. Start Shizuku, then try again."); return false; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_SHIZUKU);
            setStatus("Waiting for Shizuku permission. Approve the request, then apply again.");
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
