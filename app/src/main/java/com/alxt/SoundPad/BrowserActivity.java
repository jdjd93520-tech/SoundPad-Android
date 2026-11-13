package com.alxt.SoundPad;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URLEncoder;
import java.util.ArrayList;

public class BrowserActivity extends Activity {
    private final ArrayList<WebView> tabs = new ArrayList<>();
    private final ArrayList<Button> tabButtons = new ArrayList<>();
    private LinearLayout tabStrip;
    private EditText address;
    private FrameLayout pageHost;
    private TextView pageStatus;
    private int activeTab = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8, 8, 8, 8);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("‹", v -> { WebView w = current(); if (w != null && w.canGoBack()) w.goBack(); });
        Button forward = button("›", v -> { WebView w = current(); if (w != null && w.canGoForward()) w.goForward(); });
        Button reload = button("↻", v -> { WebView w = current(); if (w != null) w.reload(); });
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Search or enter address");
        nav.addView(back, new LinearLayout.LayoutParams(42, 48));
        nav.addView(forward, new LinearLayout.LayoutParams(42, 48));
        nav.addView(reload, new LinearLayout.LayoutParams(42, 48));
        nav.addView(address, new LinearLayout.LayoutParams(0, 48, 1));
        nav.addView(button("GO", v -> navigate()), new LinearLayout.LayoutParams(56, 48));
        root.addView(nav);

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabsScroll.addView(tabStrip);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.addView(tabsScroll, new LinearLayout.LayoutParams(0, 44, 1));
        tabRow.addView(button("+", v -> addTab("https://www.google.com")), new LinearLayout.LayoutParams(48, 44));
        root.addView(tabRow);

        pageStatus = new TextView(this);
        pageStatus.setText("Ready");
        root.addView(pageStatus);
        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        addTab("https://www.google.com");
    }

    private Button button(String label, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(action);
        return b;
    }

    private WebView current() {
        return activeTab >= 0 && activeTab < tabs.size() ? tabs.get(activeTab) : null;
    }

    private void addTab(String url) {
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String pageUrl, Bitmap favicon) {
                if (current() == view) {
                    address.setText(pageUrl);
                    pageStatus.setText("Loading…");
                }
            }
            @Override public void onPageFinished(WebView view, String pageUrl) {
                if (current() == view) {
                    address.setText(pageUrl);
                    pageStatus.setText(view.getTitle() == null ? "Page loaded" : view.getTitle());
                }
            }
        });
        int index = tabs.size();
        tabs.add(web);
        Button tab = button("Tab " + (index + 1) + " · hold to close", v -> selectTab(index));
        tab.setOnLongClickListener(v -> { closeTab(index); return true; });
        tabButtons.add(tab);
        tabStrip.addView(tab);
        pageHost.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        selectTab(index);
        web.loadUrl(url);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTab = index;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        WebView w = current();
        if (w != null) {
            address.setText(w.getUrl() == null ? "" : w.getUrl());
            pageStatus.setText(w.getTitle() == null ? "Tab " + (index + 1) : w.getTitle());
        }
    }

    private void closeTab(int index) {
        if (tabs.size() <= 1 || index < 0 || index >= tabs.size()) return;
        WebView removed = tabs.remove(index);
        pageHost.removeView(removed);
        removed.stopLoading();
        removed.destroy();
        tabStrip.removeView(tabButtons.remove(index));
        for (int i = 0; i < tabButtons.size(); i++) {
            final int newIndex = i;
            tabButtons.get(i).setText("Tab " + (i + 1) + " · hold to close");
            tabButtons.get(i).setOnClickListener(v -> selectTab(newIndex));
            tabButtons.get(i).setOnLongClickListener(v -> { closeTab(newIndex); return true; });
        }
        selectTab(Math.max(0, Math.min(index, tabs.size() - 1)));
    }

    private void navigate() {
        String value = address.getText().toString().trim();
        if (value.isEmpty()) return;
        String target = value;
        if (!value.matches("(?i)^https?://.*")) {
            if (value.contains(" ") || !value.contains(".")) {
                try { target = "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8"); }
                catch (Exception ignored) { target = "https://www.google.com"; }
            } else target = "https://" + value;
        }
        WebView w = current();
        if (w != null) w.loadUrl(target);
    }

    @Override public void onBackPressed() {
        WebView w = current();
        if (w != null && w.canGoBack()) w.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        for (WebView w : tabs) {
            try { w.stopLoading(); w.destroy(); } catch (Exception ignored) { }
        }
        tabs.clear();
        super.onDestroy();
    }
}