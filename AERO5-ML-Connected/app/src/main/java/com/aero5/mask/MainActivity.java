package com.aero5.mask;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PERM_REQ = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyWindowStyle(true); // default dark
        webView = new WebView(this);
        setContentView(webView);
        setupWebView();
        requestPerms();
    }

    private void applyWindowStyle(boolean dark) {
        int bgColor = dark ? Color.parseColor("#050A14") : Color.parseColor("#F0F6FF");
        getWindow().setStatusBarColor(bgColor);
        getWindow().setNavigationBarColor(bgColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            s.setSafeBrowsingEnabled(false);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        @JavascriptInterface
        public void onEvent(String action, String jsonStr) {
            try {
                JSONObject data = new JSONObject(jsonStr);
                switch (action) {
                    case "LOGIN":
                        startBgService(data.optString("maskId"));
                        break;
                    case "LOGOUT":
                        stopBgService();
                        break;
                    case "AQI_UPDATE":
                        AeroBackgroundService.updateData(data.optInt("aqi",0), data.optInt("filter",0));
                        break;
                    case "THEME_CHANGE":
                        boolean dark = data.optBoolean("dark", true);
                        runOnUiThread(() -> applyWindowStyle(dark));
                        break;
                    case "SHOW_NOTIFICATION":
                        // Native notification fired by background service if needed
                        break;
                    case "ALERT_TOGGLE":
                        AeroBackgroundService.setAlertsEnabled(data.optBoolean("enabled", true));
                        break;
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void startBgService(String maskId) {
        Intent i = new Intent(this, AeroBackgroundService.class);
        i.putExtra("maskId", maskId);
        i.setAction(AeroBackgroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void stopBgService() {
        Intent i = new Intent(this, AeroBackgroundService.class);
        i.setAction(AeroBackgroundService.ACTION_STOP);
        startService(i);
    }

    private void requestPerms() {
        String[] perms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        boolean need = false;
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED){ need=true; break; }
        if (need) ActivityCompat.requestPermissions(this, perms, PERM_REQ);
    }

    @Override public void onBackPressed(){ if(webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onPause(){ super.onPause(); webView.onPause(); }
    @Override protected void onResume(){ super.onResume(); webView.onResume(); }
    @Override protected void onDestroy(){ super.onDestroy(); webView.destroy(); }
}
