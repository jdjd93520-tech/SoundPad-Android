package com.alxt.SoundPad;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public class AppLauncherActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 7712;
    private static final String PREFS = "pubg_display";
    private static final String ACTIVE = "active";
    private static final String ORIGINAL = "original";
    private static final String PUBG_PACKAGE = "com.tencent.ig";
    private EditText width, height;
    private TextView status;
    private Switch stretch;
    private boolean permissionPending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 24, 22, 22);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("PUBG MOBILE — STRETCHED");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Shizuku display profiles and one-tap PUBG launcher");
        subtitle.setTextColor(0xFFB8C7D9);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        TextView ratioLabel = new TextView(this);
        ratioLabel.setText("Display aspect ratio");
        ratioLabel.setTextColor(0xFFFFFFFF);
        ratioLabel.setTextSize(17);
        root.addView(ratioLabel);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        addPreset(presets, "16:9", 16, 9);
        addPreset(presets, "4:3", 4, 3);
        addPreset(presets, "3:2", 3, 2);
        addPreset(presets, "21:9", 21, 9);
        root.addView(presets);

        LinearLayout values = new LinearLayout(this);
        values.setOrientation(LinearLayout.HORIZONTAL);
        width = new EditText(this);
        width.setSingleLine(true);
        width.setInputType(2);
        width.setTextColor(0xFFFFFFFF);
        width.setHintTextColor(0xFFB8C7D9);
        width.setHint("Width px");
        height = new EditText(this);
        height.setSingleLine(true);
        height.setInputType(2);
        height.setTextColor(0xFFFFFFFF);
        height.setHintTextColor(0xFFB8C7D9);
        height.setHint("Height px");
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
        width.setText(String.valueOf(longEdge));
        height.setText(String.valueOf(Math.min(dm.widthPixels, dm.heightPixels)));
        values.addView(width, new LinearLayout.LayoutParams(0, 58, 1));
        values.addView(height, new LinearLayout.LayoutParams(0, 58, 1));
        root.addView(values);

        stretch = new Switch(this);
        stretch.setText("Enable stretched display (Shizuku)");
        stretch.setTextColor(0xFFFFFFFF);
        stretch.setChecked(prefs().getBoolean(ACTIVE, false));
        root.addView(stretch);

        Button apply = button("APPLY STRETCHED RESOLUTION");
        root.addView(apply, full());
        apply.setOnClickListener(v -> applyDisplay());

        Button launch = button("OPEN PUBG MOBILE");
        root.addView(launch, full());
        launch.setOnClickListener(v -> launchPubg());

        Button reset = button("RESET DISPLAY");
        root.addView(reset, full());
        reset.setOnClickListener(v -> resetDisplay());

        status = new TextView(this);
        status.setTextColor(0xFFFFD180);
        status.setText("PUBG Mobile Global package: " + PUBG_PACKAGE + "\nStart Shizuku, grant permission, choose a ratio, apply, then launch PUBG.");
        status.setPadding(0, 18, 0, 12);
        root.addView(status);

        TextView warning = new TextView(this);
        warning.setTextColor(0xFFB8C7D9);
        warning.setTextSize(12);
        warning.setText("This changes Android's logical display size system-wide while enabled, not just PUBG. Reset restores the saved prior override. Android, the device manufacturer, or PUBG may letterbox, crop, or ignore the requested ratio; true GPU/panel stretching is not available through standard Shizuku commands.");
        root.addView(warning);
        setContentView(root);

        stretch.setOnCheckedChangeListener((v, checked) -> {
            if (checked) applyDisplay();
            else resetDisplay();
        });
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b;
    }
    private void addPreset(LinearLayout row, String label, int rw, int rh) {
        Button b = button(label);
        row.addView(b, new LinearLayout.LayoutParams(0, 52, 1));
        b.setOnClickListener(v -> {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
            int shortEdge = Math.max(200, Math.round(longEdge * (Math.min(rw, rh) / (float)Math.max(rw, rh))));
            if (rw >= rh) { width.setText(String.valueOf(longEdge)); height.setText(String.valueOf(shortEdge)); }
            else { width.setText(String.valueOf(shortEdge)); height.setText(String.valueOf(longEdge)); }
        });
    }
    private int number(EditText field) {
        try { return Math.max(200, Math.min(5000, Integer.parseInt(field.getText().toString().trim()))); }
        catch (Exception e) { return 0; }
    }
    private void applyDisplay() {
        if (!stretch.isChecked()) stretch.setChecked(true);
        if (!Shizuku.pingBinder()) { status.setText("Shizuku is not running. Start Shizuku and try again."); stretch.setChecked(false); return; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            status.setText("Waiting for Shizuku permission…");
            Shizuku.requestPermission(REQUEST_SHIZUKU);
            return;
        }
        int w = number(width), h = number(height);
        if (w == 0 || h == 0) { status.setText("Enter valid width and height values."); return; }
        new Thread(() -> {
            try {
                String current = shell("wm size");
                if (!prefs().getBoolean(ACTIVE, false)) {
                    Matcher m = Pattern.compile("Override size:\\s*(\\d+x\\d+)").matcher(current);
                    prefs().edit().putString(ORIGINAL, m.find() ? m.group(1) : "").apply();
                }
                shell("wm size " + w + "x" + h);
                prefs().edit().putBoolean(ACTIVE, true).apply();
                runOnUiThread(() -> status.setText("Applied " + w + " × " + h + " logical pixels. Now launch PUBG Mobile."));
            } catch (Exception e) {
                runOnUiThread(() -> { status.setText("Apply failed: " + e.getMessage()); stretch.setChecked(false); });
            }
        }, "PUBG-display-apply").start();
    }
    private void resetDisplay() {
        if (!prefs().getBoolean(ACTIVE, false)) { if (status != null) status.setText("No display override to reset."); return; }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            status.setText("Reconnect Shizuku and grant permission to restore the previous display setting.");
            if (stretch != null && !stretch.isChecked()) stretch.setChecked(true);
            return;
        }
        new Thread(() -> {
            try {
                String previous = prefs().getString(ORIGINAL, "");
                if (previous == null || previous.isEmpty()) shell("wm size reset");
                else shell("wm size " + previous);
                prefs().edit().putBoolean(ACTIVE, false).remove(ORIGINAL).apply();
                runOnUiThread(() -> { status.setText("Original display setting restored."); if (stretch.isChecked()) stretch.setChecked(false); });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Reset failed: " + e.getMessage()));
            }
        }, "PUBG-display-reset").start();
    }
    private void launchPubg() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(PUBG_PACKAGE);
        if (intent == null) {
            status.setText("PUBG Mobile Global was not found. This build targets package " + PUBG_PACKAGE + ".");
            return;
        }
        try { startActivity(intent); }
        catch (Exception e) { status.setText("Could not launch PUBG: " + e.getMessage()); }
    }
    private String shell(String command) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Process p = (Process)m.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("Command exit " + code);
        return out.toString();
    }
    @Override protected void onResume() {
        super.onResume();
        if (permissionPending && Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            permissionPending = false;
            applyDisplay();
        }
    }
}