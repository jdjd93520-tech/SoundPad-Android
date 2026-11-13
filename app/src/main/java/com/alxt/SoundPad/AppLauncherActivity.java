package com.alxt.SoundPad;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.DisplayMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppLauncherActivity extends Activity {
    private EditText search;
    private EditText widthField;
    private EditText heightField;
    private LinearLayout appList;
    private final ArrayList<ApplicationInfo> apps = new ArrayList<>();
    private int widthDp;
    private int heightDp;

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

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(preset("16:9", 16, 9), new LinearLayout.LayoutParams(0, 48, 1));
        presets.addView(preset("4:3", 4, 3), new LinearLayout.LayoutParams(0, 48, 1));
        presets.addView(preset("Portrait", 9, 16), new LinearLayout.LayoutParams(0, 48, 1));
        root.addView(presets);

        TextView note = new TextView(this);
        note.setText("Dimensions are requested window bounds in dp, not a device-wide resolution change. Android or the target app may ignore freeform bounds or enforce its own orientation.");
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(appList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderApps(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        loadApps();
    }

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
            android.widget.Toast.makeText(this, "Could not open app in freeform: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}