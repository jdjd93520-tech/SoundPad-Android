package com.alxt.SoundPad;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.DisplayMetrics;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public class AppLauncherActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 7712;
    private static final String PREFS = "display_override";
    private static final String KEY_ACTIVE = "stretched_active";
    private static final String KEY_ORIGINAL = "original_override";
    private EditText search, widthField, heightField;
    private LinearLayout appList;
    private CheckBox stretchedToggle;
    private TextView displayStatus;
    private final ArrayList<ApplicationInfo> apps = new ArrayList<>();
    private int widthDp, heightDp;
    private boolean pendingEnable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        widthDp = Math.max(280, (int)(dm.widthPixels / dm.density * 0.82f));
        heightDp = Math.max(420, (int)(dm.heightPixels / dm.density * 0.72f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 12);
        TextView title = new TextView(this);
        title.setText("Open another app in a freeform window");
        title.setTextSize(21);
        root.addView(title);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search installed apps");
        root.addView(search);

        LinearLayout dimensions = new LinearLayout(this);
        dimensions.setOrientation(LinearLayout.HORIZONTAL);
        widthField = new EditText(this);
        widthField.setSingleLine(true);
        widthField.setInputType(2);
        widthField.setHint("Width dp");
        widthField.setText(String.valueOf(widthDp));
        heightField = new EditText(this);
        heightField.setSingleLine(true);
        heightField.setInputType(2);
        heightField.setHint("Height dp");
        heightField.setText(String.valueOf(heightDp));
        dimensions.addView(widthField, new LinearLayout.LayoutParams(0, 52, 1));
        dimensions.addView(heightField, new LinearLayout.LayoutParams(0, 52, 1));
        root.addView(dimensions);

        stretchedToggle = new CheckBox(this);
        stretchedToggle.setText("Apply stretched aspect ratio system-wide (all apps)");
        stretchedToggle.setChecked(getPreferencesStore().getBoolean(KEY_ACTIVE, false));
        root.addView(stretchedToggle);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(preset("16:9", 16, 9), new LinearLayout.LayoutParams(0, 48, 1));
        presets.addView(preset("4:3", 4, 3), new LinearLayout.LayoutParams(0, 48, 1));
        presets.addView(preset("Portrait", 9, 16), new LinearLayout.LayoutParams(0, 48, 1));
        root.addView(presets);

        TextView note = new TextView(this);
        note.setText("Window width/height set the freeform launch bounds. The toggle separately changes Android's logical display resolution for the whole device using Shizuku, so the selected aspect ratio also applies to PUBG Mobile and other apps. Android/OEM scaling may letterbox or crop instead of physically stretching; some games may enforce their own display mode. Turn the toggle off to restore the previous display override.");
        root.addView(note);
        displayStatus = new TextView(this);
        displayStatus.setText(stretchedToggle.isChecked() ? "Stretched display override is marked on." : "System display unchanged.");
        root.addView(displayStatus);

        ScrollView scroll = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(appList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        stretchedToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) enableStretchedDisplay();
            else restoreDisplay();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderApps(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        loadApps();
        if (stretchedToggle.isChecked()) {
            displayStatus.setText("Stretched mode is saved as enabled. Reapply it if the device reset its display override.");
            enableStretchedDisplay();
        }
    }

    private SharedPreferences getPreferencesStore() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private Button preset(String label, int ratioW, int ratioH) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            int w = parse(widthField, widthDp);
            heightField.setText(String.valueOf(Math.max(1, w * ratioH / ratioW)));
            if (stretchedToggle.isChecked()) enableStretchedDisplay();
        });
        return b;
    }

    private int parse(EditText field, int fallback) {
        try { return Math.max(200, Math.min(2400, Integer.parseInt(field.getText().toString().trim()))); }
        catch (Exception e) { return fallback; }
    }

    private void loadApps() {
        try {
            PackageManager pm = getPackageManager();
            apps.clear();
            for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (pm.getLaunchIntentForPackage(info.packageName) != null) apps.add(info);
            }
            Collections.sort(apps, Comparator.comparing(a -> pm.getApplicationLabel(a).toString().toLowerCase()));
            renderApps();
        } catch (Exception e) {
            TextView error = new TextView(this);
            error.setText("Could not list installed apps: " + e.getMessage());
            appList.addView(error);
        }
    }

    private void renderApps() {
        if (appList == null) return;
        appList.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase();
        PackageManager pm = getPackageManager();
        for (ApplicationInfo info : apps) {
            String label = pm.getApplicationLabel(info).toString();
            if (!label.toLowerCase().contains(query) && !info.packageName.toLowerCase().contains(query)) continue;
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText(label + "\n" + info.packageName);
            item.setOnClickListener(v -> launchInFreeform(info));
            appList.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void launchInFreeform(ApplicationInfo info) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(info.packageName);
            if (launch == null) throw new IllegalStateException("This app has no launch activity");
            int width = parse(widthField, widthDp);
            int height = parse(heightField, heightDp);
            float density = getResources().getDisplayMetrics().density;
            int w = (int)(width * density);
            int h = (int)(height * density);
            Rect bounds = new Rect(24, 72, 24 + w, 72 + h);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(bounds);
            startActivity(launch, options.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, "Could not open app in freeform: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void enableStretchedDisplay() {
        if (!Shizuku.pingBinder()) {
            pendingEnable = false;
            displayStatus.setText("Start Shizuku and connect its service, then turn this switch on again.");
            stretchedToggle.setChecked(false);
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            pendingEnable = true;
            displayStatus.setText("Waiting for Shizuku permission. Approve the prompt, then return here.");
            Shizuku.requestPermission(SHIZUKU_REQUEST);
            return;
        }
        pendingEnable = false;
        final double ratio = (double)parse(widthField, widthDp) / (double)parse(heightField, heightDp);
        new Thread(() -> {
            try {
                String current = runPrivileged("wm size");
                SharedPreferences prefs = getPreferencesStore();
                if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                    Matcher m = Pattern.compile("Override size:\\s*(\\d+x\\d+)").matcher(current);
                    prefs.edit().putString(KEY_ORIGINAL, m.find() ? m.group(1) : "").apply();
                }
                DisplayMetrics real = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(real);
                int longEdge = Math.max(real.widthPixels, real.heightPixels);
                int targetW, targetH;
                if (ratio >= 1.0) {
                    targetW = longEdge;
                    targetH = Math.max(200, (int)Math.round(longEdge / ratio));
                } else {
                    targetH = longEdge;
                    targetW = Math.max(200, (int)Math.round(longEdge * ratio));
                }
                runPrivileged("wm size " + targetW + "x" + targetH);
                prefs.edit().putBoolean(KEY_ACTIVE, true).apply();
                runOnUiThread(() -> displayStatus.setText("System-wide logical display set to " + targetW + " × " + targetH + " px. This applies to all apps."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    displayStatus.setText("Could not apply display override: " + e.getMessage());
                    stretchedToggle.setChecked(false);
                });
            }
        }, "SoundPad-display-override").start();
    }

    private void restoreDisplay() {
        SharedPreferences prefs = getPreferencesStore();
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            displayStatus.setText("System display unchanged.");
            return;
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            displayStatus.setText("Shizuku permission is needed to restore the previous display setting. Reconnect Shizuku and turn the switch off again.");
            stretchedToggle.setChecked(true);
            return;
        }
        new Thread(() -> {
            try {
                String original = prefs.getString(KEY_ORIGINAL, "");
                if (original == null || original.isEmpty()) runPrivileged("wm size reset");
                else runPrivileged("wm size " + original);
                prefs.edit().putBoolean(KEY_ACTIVE, false).remove(KEY_ORIGINAL).apply();
                runOnUiThread(() -> displayStatus.setText("Previous display resolution override restored."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    displayStatus.setText("Restore failed: " + e.getMessage());
                    stretchedToggle.setChecked(true);
                });
            }
        }, "SoundPad-display-restore").start();
    }

    private String runPrivileged(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Process process = (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Shell command failed (" + exit + "): " + command);
        return output.toString();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingEnable && Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            pendingEnable = false;
            enableStretchedDisplay();
        }
    }
}