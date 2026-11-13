package com.alxt.SoundPad;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST = 4817;
    private static final String PUBG_PACKAGE = "com.tencent.ig";
    private static final String PREFS = "stretch_settings";
    private static final String ORIGINAL = "original_size";
    private TextView status;
    private boolean launchAfterPermission;
    private final Shizuku.OnRequestPermissionResultListener permissionListener =
        (requestCode, grantResult) -> {
            if (requestCode == REQUEST) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    applyStretch(launchAfterPermission);
                } else {
                    launchAfterPermission = false;
                    status.setText("Shizuku permission denied. Approve it in Shizuku and try again.");
                }
            }
        };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permissionListener);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(24, 36, 24, 24);

        TextView title = new TextView(this);
        title.setText("PUBG Stretch");
        title.setTextSize(28);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Apply a 16:9 Android display override with Shizuku, then open PUBG Mobile.");
        subtitle.setTextSize(16);
        subtitle.setPadding(0, 14, 0, 24);
        root.addView(subtitle);

        Button open = new Button(this);
        open.setText("APPLY 16:9 + OPEN PUBG");
        root.addView(open, new LinearLayout.LayoutParams(-1, -2));
        open.setOnClickListener(v -> {
            launchAfterPermission = true;
            ensurePermission();
        });

        Button apply = new Button(this);
        apply.setText("APPLY 16:9 ONLY");
        root.addView(apply, new LinearLayout.LayoutParams(-1, -2));
        apply.setOnClickListener(v -> {
            launchAfterPermission = false;
            ensurePermission();
        });

        Button reset = new Button(this);
        reset.setText("RESTORE ORIGINAL DISPLAY");
        root.addView(reset, new LinearLayout.LayoutParams(-1, -2));
        reset.setOnClickListener(v -> restoreDisplay());

        status = new TextView(this);
        status.setText("Start Shizuku and authorize PUBG Stretch. The 16:9 override affects the whole device.");
        status.setTextSize(14);
        status.setPadding(0, 22, 0, 0);
        root.addView(status);
        setContentView(root);
    }

    private void ensurePermission() {
        if (!Shizuku.pingBinder()) {
            status.setText("Shizuku is not running. Start Shizuku, then tap the button again.");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            applyStretch(launchAfterPermission);
        } else {
            status.setText("Waiting for Shizuku authorization…");
            Shizuku.requestPermission(REQUEST);
        }
    }

    private void applyStretch(boolean launchPubg) {
        status.setText("Applying 16:9 display override…");
        new Thread(() -> {
            try {
                String current = runShell("wm size");
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (!prefs.contains(ORIGINAL)) {
                    Matcher m = Pattern.compile("Override size:\\s*(\\d+x\\d+)").matcher(current);
                    prefs.edit().putString(ORIGINAL, m.find() ? m.group(1) : "").apply();
                }
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
                int shortEdge = Math.max(200, Math.round(longEdge * 9f / 16f));
                int width = dm.widthPixels >= dm.heightPixels ? longEdge : shortEdge;
                int height = dm.widthPixels >= dm.heightPixels ? shortEdge : longEdge;
                runShell("wm size " + width + "x" + height);
                runOnUiThread(() -> {
                    status.setText("Applied logical display size " + width + " × " + height + ". Opening PUBG…");
                    if (launchPubg) openPubg();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not apply stretch: " + e.getMessage()));
            }
        }, "pubg-stretch").start();
    }

    private void restoreDisplay() {
        if (!Shizuku.pingBinder()) {
            status.setText("Start Shizuku to restore the previous display size.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            launchAfterPermission = false;
            Shizuku.requestPermission(REQUEST);
            status.setText("Grant Shizuku permission, then tap restore again.");
            return;
        }
        new Thread(() -> {
            try {
                String original = getSharedPreferences(PREFS, MODE_PRIVATE).getString(ORIGINAL, "");
                if (original == null || original.isEmpty()) runShell("wm size reset");
                else runShell("wm size " + original);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(ORIGINAL).apply();
                runOnUiThread(() -> status.setText("Original display size restored."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Restore failed: " + e.getMessage()));
            }
        }, "pubg-stretch-reset").start();
    }

    private String runShell(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Process process = (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int result = process.waitFor();
        if (result != 0) throw new IllegalStateException("Shell command returned " + result);
        return output.toString();
    }

    private void openPubg() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PUBG_PACKAGE);
        if (launch == null) {
            status.setText("16:9 is applied, but PUBG Mobile (Global: com.tencent.ig) was not found. Install the Global version or use its launcher package.");
            return;
        }
        try {
            startActivity(launch);
        } catch (Exception e) {
            status.setText("PUBG launch failed: " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}