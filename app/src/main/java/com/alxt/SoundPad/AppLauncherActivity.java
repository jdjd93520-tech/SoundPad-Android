package com.alxt.SoundPad;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;

public class AppLauncherActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 7712;
    private static final String PUBG_PACKAGE = "com.tencent.ig";
    private EditText width, height;
    private TextView status;
    private boolean permissionPending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 24, 22, 22);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("PUBG MOBILE — FREEFORM");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Launch PUBG in its own resizable window");
        subtitle.setTextColor(0xFFB8C7D9);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        TextView ratioLabel = new TextView(this);
        ratioLabel.setText("PUBG window aspect ratio");
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
        width = field("Window width px");
        height = field("Window height px");
        Point screen = screenSize();
        width.setText(String.valueOf(Math.max(screen.x, screen.y)));
        height.setText(String.valueOf(Math.min(screen.x, screen.y)));
        values.addView(width, new LinearLayout.LayoutParams(0, 58, 1));
        values.addView(height, new LinearLayout.LayoutParams(0, 58, 1));
        root.addView(values);

        Button launch = button("OPEN PUBG IN FREEFORM");
        root.addView(launch, full());
        launch.setOnClickListener(v -> launchPubg());

        status = new TextView(this);
        status.setTextColor(0xFFFFD180);
        status.setPadding(0, 18, 0, 12);
        status.setText("Target: PUBG Mobile Global (" + PUBG_PACKAGE + "). Choose a window size, then launch.");
        root.addView(status);

        TextView warning = new TextView(this);
        warning.setTextColor(0xFFB8C7D9);
        warning.setTextSize(12);
        warning.setText("This version does NOT change Android's display resolution or affect other apps. Freeform window support must be enabled by your Android device. PUBG may refuse resizable/windowed mode, and a freeform window is not the same as full-screen GPU stretching.");
        root.addView(warning);
        setContentView(root);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(2);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFFB8C7D9);
        e.setHint(hint);
        return e;
    }

    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private Point screenSize() {
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        return p;
    }

    private void addPreset(LinearLayout row, String label, int rw, int rh) {
        Button b = button(label);
        row.addView(b, new LinearLayout.LayoutParams(0, 52, 1));
        b.setOnClickListener(v -> {
            Point p = screenSize();
            int longEdge = Math.max(p.x, p.y);
            int shortEdge = Math.max(200, Math.round(longEdge * (Math.min(rw, rh) / (float)Math.max(rw, rh))));
            width.setText(String.valueOf(longEdge));
            height.setText(String.valueOf(shortEdge));
        });
    }

    private int number(EditText field) {
        try { return Math.max(200, Math.min(5000, Integer.parseInt(field.getText().toString().trim()))); }
        catch (Exception e) { return 0; }
    }

    private void launchPubg() {
        int w = number(width);
        int h = number(height);
        if (w == 0 || h == 0) {
            status.setText("Enter valid window width and height values.");
            return;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(PUBG_PACKAGE);
        if (launchIntent == null) {
            status.setText("PUBG Mobile Global was not found. This build targets " + PUBG_PACKAGE + ".");
            return;
        }
        ComponentName component = launchIntent.getComponent();
        if (component == null) {
            status.setText("Could not resolve PUBG's launch activity.");
            return;
        }
        if (!Shizuku.pingBinder()) {
            status.setText("Start Shizuku first. It is used only to request the freeform launch.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            status.setText("Waiting for Shizuku permission…");
            Shizuku.requestPermission(REQUEST_SHIZUKU);
            return;
        }
        launchFreeform(component, w, h);
    }

    private void launchFreeform(ComponentName component, int w, int h) {
        Point p = screenSize();
        int displayW = Math.max(1, p.x);
        int displayH = Math.max(1, p.y);
        int targetW = Math.min(w, displayW);
        int targetH = Math.min(h, displayH);
        int left = Math.max(0, (displayW - targetW) / 2);
        int top = Math.max(0, (displayH - targetH) / 2);
        int right = left + targetW;
        int bottom = top + targetH;
        String flattened = component.flattenToShortString();

        // Launch PUBG in freeform, find its task ID, then resize only that task.
        // This does not use wm size/density and does not alter the device display.
        String command = "am start --user current --windowingMode 5 -n '" + flattened + "'"
                + " >/dev/null 2>&1; sleep 2; "
                + "TASK_ID=$(dumpsys activity activities | awk -v pkg='" + PUBG_PACKAGE + "' "
                + "'/Task\\{/ { id=\\"\\"; if (match($0, /#[0-9]+/)) id=substr($0, RSTART+1, RLENGTH-1) } "
                + "index($0,pkg) && id != \\"\\" { print id; exit }'); "
                + "if [ -z \\"$TASK_ID\\" ]; then echo 'PUBG task was not found after launch'; exit 2; fi; "
                + "am task resize $TASK_ID " + left + " " + top + " " + right + " " + bottom;

        status.setText("Opening PUBG, then resizing its window to " + targetW + " × " + targetH + "…");
        new Thread(() -> {
            try {
                String result = shell(command);
                runOnUiThread(() -> status.setText("PUBG launch and resize command completed. "
                        + (result.trim().isEmpty() ? "If PUBG stayed full-screen, this Android build or the game may block freeform resizing."
                        : result.trim())));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("PUBG launch/resize failed: " + e.getMessage()
                        + ". Freeform support and task resizing depend on your Android version."));
            }
        }, "PUBG-freeform-launch").start();
    }

    private String shell(String command) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Process p = (Process)m.invoke(null, new String[]{"sh", "-c", command}, null, null);
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
             BufferedReader errors = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            while ((line = errors.readLine()) != null) out.append(line).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("shell exit " + code + (out.length() == 0 ? "" : ": " + out.toString().trim()));
        return out.toString();
    }

    @Override protected void onResume() {
        super.onResume();
        if (permissionPending && Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            permissionPending = false;
            launchPubg();
        }
    }
}
