package com.alxt.SoundPad;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST = 4817;
    private EditText width, height;
    private TextView status;
    private boolean pendingApply;
    private final Shizuku.OnRequestPermissionResultListener permissionListener =
        (code, result) -> {
            if (code == REQUEST) {
                if (result == PackageManager.PERMISSION_GRANTED && pendingApply) apply();
                else status.setText("Shizuku permission was not granted.");
                pendingApply = false;
            }
        };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(24, 28, 24, 24);
        root.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("PUBG STRETCH");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(27);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        TextView note = new TextView(this);
        note.setText("Changes Android's whole-screen logical resolution using Shizuku. Other apps and the phone UI are affected too.");
        note.setTextColor(0xFFD6E0EA);
        note.setTextSize(14);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 14, 0, 18);
        root.addView(note, full());

        TextView label = new TextView(this);
        label.setText("Stretch resolution (pixels)");
        label.setTextColor(0xFFFFFFFF);
        root.addView(label, full());

        LinearLayout fields = new LinearLayout(this);
        width = field("Width");
        height = field("Height");
        fields.addView(width, new LinearLayout.LayoutParams(0, 56, 1));
        fields.addView(height, new LinearLayout.LayoutParams(0, 56, 1));
        root.addView(fields, full());

        LinearLayout presets = new LinearLayout(this);
        addPreset(presets, "16:9", 16, 9);
        addPreset(presets, "4:3", 4, 3);
        addPreset(presets, "3:2", 3, 2);
        addPreset(presets, "21:9", 21, 9);
        root.addView(presets, full());

        Button apply = button("APPLY STRETCH (10 MINUTES)");
        root.addView(apply, full());
        apply.setOnClickListener(v -> requestApply());

        Button reset = button("RESET TO NORMAL NOW");
        root.addView(reset, full());
        reset.setOnClickListener(v -> restore());

        Button open = button("OPEN PUBG MOBILE");
        root.addView(open, full());
        open.setOnClickListener(v -> openPubg());

        status = new TextView(this);
        status.setTextColor(0xFFFFD180);
        status.setTextSize(14);
        status.setPadding(0, 20, 0, 0);
        status.setText("Start Shizuku, grant permission, then apply a preset. Automatic restore: 10 minutes, screen-off, or reboot.");
        root.addView(status, full());
        setContentView(root);
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(2);
        e.setHint(hint);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF9EACBA);
        return e;
    }
    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }
    private void addPreset(LinearLayout row, String name, int rw, int rh) {
        Button b = button(name);
        row.addView(b, new LinearLayout.LayoutParams(0, 50, 1));
        b.setOnClickListener(v -> {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
            width.setText(String.valueOf(longEdge));
            height.setText(String.valueOf(Math.max(200, longEdge * Math.min(rw, rh) / Math.max(rw, rh))));
        });
    }
    private void requestApply() {
        if (!Shizuku.pingBinder()) {
            status.setText("Shizuku is not running. Start Shizuku and try again.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            pendingApply = true;
            Shizuku.requestPermission(REQUEST);
            status.setText("Waiting for Shizuku permission…");
            return;
        }
        apply();
    }
    private void apply() {
        int w = parse(width), h = parse(height);
        if (w < 200 || h < 200) {
            status.setText("Enter valid width and height values.");
            return;
        }
        Intent i = new Intent(this, DisplayRestoreService.class)
            .setAction(DisplayRestoreService.ACTION_APPLY)
            .putExtra(DisplayRestoreService.EXTRA_WIDTH, w)
            .putExtra(DisplayRestoreService.EXTRA_HEIGHT, h);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            status.setText("Stretch requested. The display will automatically restore after 10 minutes or when the screen turns off.");
        } catch (Exception e) {
            status.setText("Could not start protected display timer: " + e.getMessage());
        }
    }
    private int parse(EditText e) {
        try { return Math.max(200, Math.min(5000, Integer.parseInt(e.getText().toString().trim()))); }
        catch (Exception ex) { return 0; }
    }
    private void restore() {
        Intent i = new Intent(this, DisplayRestoreService.class).setAction(DisplayRestoreService.ACTION_RESTORE);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            status.setText("Restore requested. Keep Shizuku running until the original display settings are restored.");
        } catch (Exception e) { status.setText("Could not request restore: " + e.getMessage()); }
    }
    private void openPubg() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.ig");
        if (i == null) status.setText("PUBG Mobile Global (com.tencent.ig) was not found.");
        else startActivity(i);
    }
    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}