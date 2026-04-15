package com.aero5.mask;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PERM_REQ = 100;

    // BLE Variables
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private boolean isScanning = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (downloadId != -1) {
                installApk(downloadId);
            }
        }
    };

    private void installApk(long downloadId) {
        DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Uri apkUri = downloadManager.getUriForDownloadedFile(downloadId);

        if (apkUri != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                // Check if we can install from unknown sources (Android 8.0+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                        return;
                    }
                }
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Common UUIDs for ESP32 BLE provisioning or custom service
    private static final UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

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
        initBluetooth();
        requestPermissions();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void initBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
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

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                android.util.Log.d("AERO5_JS", consoleMessage.message() + " -- From line "
                        + consoleMessage.lineNumber() + " of "
                        + consoleMessage.sourceId());
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Allow the 192.168.4.1 portal to load inside the WebView/iframe
                if (url.contains("192.168.4.1")) {
                    return false;
                }
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
                    case "OPEN_URL":
                        String url = data.optString("url");
                        if (url.startsWith("http://192.168.4.1")) {
                            // Specifically for the hardware portal, we might want to ensure Chrome opens it
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.setPackage("com.android.chrome");
                            try {
                                startActivity(intent);
                            } catch (android.content.ActivityNotFoundException e) {
                                // Fallback to default browser
                                intent.setPackage(null);
                                startActivity(intent);
                            }
                        } else {
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            startActivity(browserIntent);
                        }
                        break;
                    case "DOWNLOAD_UPDATE":
                        String downloadUrl = data.optString("url");
                        handleDownload(downloadUrl);
                        break;
                    case "BLE_SCAN":
                        startBleScan();
                        break;
                    case "BLE_CONNECT":
                        String address = data.optString("address");
                        connectToDevice(address);
                        break;
                    case "BLE_SEND_WIFI":
                        String ssid = data.optString("ssid");
                        String pass = data.optString("pass");
                        sendWifiOverBle(ssid, pass);
                        break;
                    case "GET_STREET_NAME":
                        double lat = data.optDouble("lat");
                        double lon = data.optDouble("lon");
                        getStreetName(lat, lon);
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void handleDownload(String url) {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("AERO5 Update");
            request.setDescription("Downloading new version...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(MainActivity.this, android.os.Environment.DIRECTORY_DOWNLOADS, "AERO5_Update.apk");

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);

            android.widget.Toast.makeText(MainActivity.this, "Update Started. Check notifications.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // BLE Methods
    private void startBleScan() {
        if (bluetoothLeScanner == null) return;
        if (isScanning) return;

        isScanning = true;
        handler.postDelayed(() -> {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
            sendToJs("ble_scan_finished", new JSONObject());
        }, 10000);

        bluetoothLeScanner.startScan(scanCallback);
        sendToJs("ble_scan_started", new JSONObject());
    }

    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", device.getName() != null ? device.getName() : "Unknown");
                obj.put("address", device.getAddress());
                obj.put("rssi", result.getRssi());
                sendToJs("ble_device_found", obj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    private void connectToDevice(String address) {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) return;
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
                sendToJs("ble_connected", new JSONObject());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sendToJs("ble_disconnected", new JSONObject());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendToJs("ble_services_discovered", new JSONObject());
            }
        }
    };

    private void sendWifiOverBle(String ssid, String pass) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
        if (characteristic == null) return;

        String data = ssid + ":" + pass;
        characteristic.setValue(data.getBytes());
        bluetoothGatt.writeCharacteristic(characteristic);
        sendToJs("ble_wifi_sent", new JSONObject());
    }

    // Geocoder Methods
    private void getStreetName(double lat, double lon) {
        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    String street = addresses.get(0).getThoroughfare();
                    if (street == null) street = addresses.get(0).getLocality();
                    
                    JSONObject obj = new JSONObject();
                    obj.put("street", street != null ? street : "Unknown Road");
                    obj.put("lat", lat);
                    obj.put("lon", lon);
                    
                    handler.post(() -> sendToJs("street_name_resolved", obj));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void sendToJs(String event, JSONObject data) {
        String script = String.format("window.dispatchEvent(new CustomEvent('%s', {detail: %s}))", event, data.toString());
        webView.evaluateJavascript(script, null);
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
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        boolean needReq = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needReq = true;
                break;
            }
        }
        if (needReq) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQ);
        }
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
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Exception e) {
            // Already unregistered or not registered
        }
        webView.destroy();
    }
}
