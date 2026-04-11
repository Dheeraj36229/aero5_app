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

import androidx.appcompat.app.AlertDialog;
import android.content.pm.PackageInfo;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Map;
import android.net.Uri;
import android.app.DownloadManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Environment;
import androidx.core.content.FileProvider;
import java.io.File;
import android.widget.Toast;


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
        checkUpdate();
    }

    private void checkUpdate() {
        FirebaseDatabase.getInstance().getReference("app_update")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            Long latestVersion = snapshot.child("latest_version").getValue(Long.class);
                            String apkUrl = snapshot.child("apk_url").getValue(String.class);
                            
                            if (latestVersion == null || apkUrl == null) return;

                            long currentVersion = 0;
                            try {
                                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    currentVersion = pInfo.getLongVersionCode();
                                } else {
                                    currentVersion = pInfo.versionCode;
                                }
                            } catch (Exception e) { e.printStackTrace(); }

                            if (latestVersion > currentVersion) {
                                showUpdateDialog(apkUrl);
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void showUpdateDialog(String apkUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("A new version of the app is available. Please update to get the latest features.")
                .setPositiveButton("Update Now", (dialog, which) -> {
                    downloadAndInstallApk(apkUrl);
                })
                .setNegativeButton("Later", null)
                .setCancelable(false)
                .show();
    }

    private void downloadAndInstallApk(String url) {
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show();
        String destination = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) + "/update.apk";
        File file = new File(destination);
        if (file.exists()) file.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle("App Update");
        request.setDescription("Downloading new version...");
        request.setDestinationUri(Uri.fromFile(file));

        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        final long downloadId = manager.enqueue(request);

        BroadcastReceiver onComplete = new BroadcastReceiver() {
            public void onReceive(Context ctxt, Intent intent) {
                installApk(file);
                unregisterReceiver(this);
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void installApk(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.setDataAndType(contentUri, "application/vnd.android.package-archive");
            startActivity(install);
        } else {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        }
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

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncWithService();
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void syncWithService() {
        // When app returns to foreground/reloads, update UI with service's latest data
        webView.evaluateJavascript("if(typeof ab === 'function') ab('SERVICE_SYNC', {});", null);
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
                    case "SERVICE_SYNC":
                        runOnUiThread(this::syncWithService);
                        break;
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        private void syncWithService() {
            webView.evaluateJavascript("if(typeof syncFromFirebase === 'function') syncFromFirebase();", null);
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
