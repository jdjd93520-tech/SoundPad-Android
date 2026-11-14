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
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 42;
    private static final String[] IPAD_4_3 = {"800x600", "960x720", "1024x768", "1280x960", "1440x1080", "1600x1200", "1920x1440"};\n    private static final String[] CLASSIC_5_4 = {"800x640", "1000x800", "1280x1024", "1600x1280"};\n    private static final String[] DRONE_3_2 = {"1200x800", "1440x960", "1620x1080", "1920x1280"};\n    private static final String[] WIDE_16_10 = {"1280x800", "1600x1000"};\n    private static final String[] RESOLUTIONS = IPAD_4_3;
    private String selectedMode = "stretched";
    private TextView statusText, displayText, modeDescription;
    private Button stretchedButton, centerButton, previewButton, preview30Button, forceLandscapeButton;
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

        forceLandscapeButton = actionButton("FORCE LANDSCAPE (30 MIN)", Color.rgb(37, 118, 91), Color.WHITE);
        LinearLayout.LayoutParams forceLandscapeLp = params(-1, dp(54));
        forceLandscapeLp.topMargin = dp(14);
        root.addView(forceLandscapeButton, forceLandscapeLp);
        forceLandscapeButton.setOnClickListener(v -> forceLandscapeNow());

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

        LinearLayout ratioButtons = new LinearLayout(this);
        ratioButtons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ratioLp = params(-1, dp(48)); ratioLp.topMargin = dp(8);
        root.addView(ratioButtons, ratioLp);
        addRatioButton(ratioButtons, "iPad 4:3", IPAD_4_3, true);
        addRatioButton(ratioButtons, "5:4", CLASSIC_5_4, false);
        addRatioButton(ratioButtons, "Drone 3:2", DRONE_3_2, false);
        addRatioButton(ratioButtons, "16:10", WIDE_16_10, false);

        pairPreviewCheck = new CheckBox(this);
        pairPreviewCheck.setText("Enable manual A/B resolution toggle");
        pairPreviewCheck.setTextColor(fg);
        pairPreviewCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams pairLp = params(-1, -2); pairLp.topMargin = dp(8);
        root.addView(pairPreviewCheck, pairLp);
        toggleResolutionButton = actionButton("TOGGLE A / B RESOLUTION", Color.rgb(37, 118, 91), Color.WHITE);
        LinearLayout.LayoutParams toggleLp = params(-1, dp(54)); toggleLp.topMargin = dp(8);
        root.addView(toggleResolutionButton, toggleLp);
        toggleResolutionButton.setEnabled(false);
        pairPreviewCheck.setOnCheckedChangeListener((button, checked) -> toggleResolutionButton.setEnabled(checked));
        toggleResolutionButton.setOnClickListener(v -> toggleResolutionManually());

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

        TextView safety = label("PREVIEW 20 SEC and PREVIEW 30 MIN temporarily apply the selected resolution, then attempt to restore your saved settings automatically. There is no permanent apply option. You can restore sooner with RESTORE ORIGINAL DISPLAY. A/B switching is manual only; rapid automatic flicker has been intentionally excluded. Keep Shizuku running and available for automatic restore.", 12, muted, false);
        LinearLayout.LayoutParams safetyLp = params(-1, -2); safetyLp.topMargin = dp(20);
        root.addView(safety, safetyLp);

        TextView note = label("Presets are landscape aspect-ratio groups: iPad-style 4:3, classic 5:4, drone-style 3:2, and 16:10. Higher logical resolutions do not add physical pixels and may make interface elements smaller. This does not force a game to use the selected viewport or guarantee a slow-motion effect. STRETCHED asks Android to disable aspect-preserving display scaling, which may fill the panel on supported firmware; some Motorola builds may still letterbox. Android normally remaps touch coordinates, but exact game touch behavior must be tested on-device. Force Landscape is a 30-minute system rotation lock; PUBG can still override it on some Android versions.", 12, muted, false);
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
        if (preview30Button != null) preview30Button.setEnabled(stretched);\n        if (toggleResolutionButton != null) toggleResolutionButton.setEnabled(stretched && pairPreviewCheck != null && pairPreviewCheck.isChecked());
        modeDescription.setText(stretched
                ? "STRETCHED applies the selected logical resolution. Choose one of the presets below and use a timed preview. The display automatically attempts to return to its saved settings when the preview ends."
                : "CENTER / NATIVE restores the saved original resolution, DPI, and scaling settings. Android does not provide a universal system-wide GPU-style centered mode.");
    }

    private void addRatioButton(LinearLayout parent, String label, String[] values, boolean first) {
        Button b = actionButton(label, first ? Color.rgb(48, 113, 211) : Color.rgb(40, 52, 72), Color.WHITE);
        b.setTextSize(10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.leftMargin = dp(2); lp.rightMargin = dp(2);
        parent.addView(b, lp);
        b.setOnClickListener(v -> {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            resolutionSpinner.setAdapter(adapter);
            resolutionSpinner.setSelection(0);
            showingResolutionA = true;
            setStatus(label + " aspect presets loaded. Select a resolution.");
        });
    }

    private void toggleResolutionManually() {
        if (!pairPreviewCheck.isChecked()) {
            setStatus("Check the A/B toggle box first.");
            return;
        }
        // A manual switch is deliberately user-triggered; no rapid automatic flicker loop.
        showingResolutionA = !showingResolutionA;
        String[] values = currentAspectValues();
        int index = showingResolutionA ? 0 : Math.min(1, values.length - 1);
        String target = values[index];
        runPreview(target, 30 * 60 * 1000L, "30 minutes");
        setStatus("Switched to " + (showingResolutionA ? "A: " : "B: ") + target
                + ". Tap TOGGLE A / B RESOLUTION to switch back.");
    }

    private String[] currentAspectValues() {
        Object adapter = resolutionSpinner.getAdapter();
        if (adapter instanceof ArrayAdapter) {
            ArrayAdapter<?> a = (ArrayAdapter<?>) adapter;
            String[] values = new String[a.getCount()];
            for (int i = 0; i < a.getCount(); i++) values[i] = String.valueOf(a.getItem(i));
            return values;
        }
        return IPAD_4_3;
    }

    private String selectedResolution() {
        Object value = resolutionSpinner.getSelectedItem();
        return value == null ? RESOLUTIONS[0] : value.toString();
    }

    private void setActionsEnabled(boolean enabled) {
        previewButton.setEnabled(enabled);
        preview30Button.setEnabled(enabled && "stretched".equals(selectedMode));
        if (forceLandscapeButton != null) forceLandscapeButton.setEnabled(enabled);
        resolutionSpinner.setEnabled(enabled && "stretched".equals(selectedMode));\n        if (toggleResolutionButton != null) toggleResolutionButton.setEnabled(enabled && "stretched".equals(selectedMode) && pairPreviewCheck.isChecked());
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