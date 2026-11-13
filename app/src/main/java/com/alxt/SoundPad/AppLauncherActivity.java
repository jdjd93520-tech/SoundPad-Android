package com.alxt.SoundPad;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public class AppLauncherActivity extends Activity {
    static final String PREFS = "display_override";
    static final String KEY_ACTIVE = "stretched_active";
    static final String KEY_ORIGINAL = "original_override";
    static final String KEY_PENDING_RESTORE = "pending_restore";
    static final String KEY_DEADLINE = "restore_deadline";
    private static final int SHIZUKU_REQUEST = 7712;
    private EditText search, widthField, heightField;
    private LinearLayout appList;
    private CheckBox stretchedToggle;
    private TextView displayStatus;
    private final ArrayList<ApplicationInfo> apps = new ArrayList<>();
    private int widthDp, heightDp;
    private boolean pendingEnable;
    private boolean initializing = true;
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) restoreDisplay("Screen turned off; original display restored.");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        widthDp = Math.max(280, (int)(dm.widthPixels / dm.density * 0.82f));
        heightDp = Math.max(420, (int)(dm.heightPixels / dm.density * 0.72f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 12);
        TextView title = new TextView(this);
        title.setText("SoundPad Display Launcher");
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
        stretchedToggle.setText("Stretch the entire phone display");
        root.addView(stretchedToggle);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(preset("16:9", 16, 9), new LinearLayout.LayoutParams(0, 48, 1));
        presets.addView(preset("4:3", 4, 3), new LinearLayout.LayoutParams(0, 48, 1));
        presets.addView(preset("Portrait", 9, 16), new LinearLayout.LayoutParams(0, 48, 1));
        root.addView(presets);

        TextView note = new TextView(this);
        note.setText("The display override affects the whole phone. It automatically restores after 10 minutes, when the screen turns off, or after reboot when Shizuku is available. Android may letterbox or crop rather than physically stretch.");
        root.addView(note);
        displayStatus = new TextView(this);
        root.addView(displayStatus);

        Button reset = new Button(this);
        reset.setText("Restore original display now");
        reset.setOnClickListener(v -> restoreDisplay("Original display restored."));
        root.addView(reset);

        ScrollView scroll = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(appList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        stretchedToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) enableStretchedDisplay();
            else restoreDisplay("Original display restored.");
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderApps(); }
            @Override public void afterTextChanged(Editable e) { }
        });

        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        loadApps();
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_ACTIVE, false)) {
            stretchedToggle.setChecked(true);
            long deadline = p.getLong(KEY_DEADLINE, 0L);
            if (deadline > 0 && System.currentTimeMillis() >= deadline) restoreDisplay("Safety timeout expired; original display restored.");
            else displayStatus.setText("Stretch is active. Automatic restore is scheduled.");
        } else if (p.getBoolean(KEY_PENDING_RESTORE, false)) {
            restoreDisplay("Finishing pending display recovery.");
        } else {
            displayStatus.setText("Display unchanged.");
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private Button preset(String label, int ratioW, int ratioH) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            int w = parse(widthField, widthDp);
            heightField.setText(String.valueOf(Math.max(1, w * ratioH / ratioW)));
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
            float density = getResources().getDisplayMetrics().density;
            int w = (int)(parse(widthField, widthDp) * density);
            int h = (int)(parse(heightField, heightDp) * density);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(new Rect(24, 72, 24 + w, 72 + h));
            startActivity(launch, options.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, "Freeform launch unavailable: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void enableStretchedDisplay() {
        if (!Shizuku.pingBinder()) {
            displayStatus.setText("Start Shizuku and connect its service, then try again.");
            stretchedToggle.setChecked(false);
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            pendingEnable = true;
            displayStatus.setText("Approve the Shizuku permission prompt.");
            Shizuku.requestPermission(SHIZUKU_REQUEST);
            return;
        }
        pendingEnable = false;
        final double ratio = (double)parse(widthField, widthDp) / (double)parse(heightField, heightDp);
        new Thread(() -> {
            try {
                SharedPreferences p = prefs();
                String current = runPrivileged("wm size");
                if (!p.getBoolean(KEY_ACTIVE, false)) {
                    Matcher m = Pattern.compile("Override size:\\s*(\\d+x\\d+)").matcher(current);
                    p.edit().putString(KEY_ORIGINAL, m.find() ? m.group(1) : "").apply();
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
                long deadline = System.currentTimeMillis() + 10L * 60L * 1000L;
                p.edit().putBoolean(KEY_ACTIVE, true).putBoolean(KEY_PENDING_RESTORE, false)
                    .putLong(KEY_DEADLINE, deadline).apply();
                scheduleRestore(this, deadline);
                runOnUiThread(() -> displayStatus.setText("Whole-display override active: " + targetW + " × " + targetH + " px. Auto-restores within 10 minutes."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    displayStatus.setText("Could not apply display override: " + e.getMessage());
                    stretchedToggle.setChecked(false);
                });
            }
        }, "SoundPad-display-override").start();
    }

    private void restoreDisplay(String message) {
        SharedPreferences p = prefs();
        if (!p.getBoolean(KEY_ACTIVE, false) && !p.getBoolean(KEY_PENDING_RESTORE, false)) {
            displayStatus.setText("Display unchanged.");
            stretchedToggle.setChecked(false);
            return;
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            p.edit().putBoolean(KEY_PENDING_RESTORE, true).apply();
            displayStatus.setText("Restore is pending. Reconnect Shizuku and open SoundPad to finish recovery.");
            if (stretchedToggle.isChecked()) stretchedToggle.setChecked(false);
            return;
        }
        new Thread(() -> {
            try {
                restoreWithShizuku(this);
                runOnUiThread(() -> {
                    displayStatus.setText(message);
                    stretchedToggle.setChecked(false);
                });
            } catch (Exception e) {
                p.edit().putBoolean(KEY_PENDING_RESTORE, true).apply();
                runOnUiThread(() -> displayStatus.setText("Restore failed; reconnect Shizuku and press Restore: " + e.getMessage()));
            }
        }, "SoundPad-display-restore").start();
    }

    static void scheduleRestore(Context context, long deadline) {
        AlarmManager am = (AlarmManager)context.getSystemService(ALARM_SERVICE);
        PendingIntent pi = restorePendingIntent(context);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pi);
    }

    static PendingIntent restorePendingIntent(Context context) {
        Intent i = new Intent(context, DisplayRecoveryReceiver.class).setAction(DisplayRecoveryReceiver.ACTION_TIMEOUT);
        return PendingIntent.getBroadcast(context, 7713, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void restoreWithShizuku(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String original = p.getString(KEY_ORIGINAL, "");
        if (original == null || original.isEmpty()) runPrivilegedStatic("wm size reset");
        else runPrivilegedStatic("wm size " + original);
        p.edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_PENDING_RESTORE, false)
            .remove(KEY_ORIGINAL).remove(KEY_DEADLINE).apply();
        AlarmManager am = (AlarmManager)context.getSystemService(ALARM_SERVICE);
        am.cancel(restorePendingIntent(context));
    }

    static void runPrivilegedStatic(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Process process = (Process)method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Shell command failed (" + exit + "): " + output);
    }

    private String runPrivileged(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Process process = (Process)method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Shell command failed (" + exit + "): " + output);
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
