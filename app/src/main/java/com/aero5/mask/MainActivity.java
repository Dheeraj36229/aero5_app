package com.aero5.mask;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
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

        // Full-screen immersive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
        getWindow().setStatusBarColor(android.graphics.Color.parseColor("#070b14"));
        getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#070b14"));

        webView = new WebView(this);
        setContentView(webView);
        setupWebView();
        requestPermissions();
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
        s.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        // JavaScript → Android bridge
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Firebase/API calls — let WebView handle
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    /** Bridge: HTML can call window.AndroidBridge.onEvent(action, json) */
    private class AndroidBridge {
        @JavascriptInterface
        public void onEvent(String action, String jsonStr) {
            try {
                JSONObject data = new JSONObject(jsonStr);
                switch (action) {
                    case "LOGIN":
                        String maskId = data.optString("maskId");
                        startBackgroundService(maskId);
                        break;
                    case "LOGOUT":
                        stopBackgroundService();
                        break;
                    case "AQI_UPDATE":
                        int aqi = data.optInt("aqi", 0);
                        int filter = data.optInt("filter", 0);
                        AeroBackgroundService.updateData(aqi, filter);
                        break;
                    case "SYNC":
                        // ThingSpeak sync triggered from JS
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void startBackgroundService(String maskId) {
        Intent intent = new Intent(this, AeroBackgroundService.class);
        intent.putExtra("maskId", maskId);
        intent.setAction(AeroBackgroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopBackgroundService() {
        Intent intent = new Intent(this, AeroBackgroundService.class);
        intent.setAction(AeroBackgroundService.ACTION_STOP);
        startService(intent);
    }

    private void requestPermissions() {
        String[] perms = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            };
        }
        boolean needReq = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needReq = true; break;
            }
        }
        if (needReq) ActivityCompat.requestPermissions(this, perms, PERM_REQ);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webView.destroy();
    }
}
