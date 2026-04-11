package com.aero5.mask;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.*;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.*;
import com.google.firebase.database.*;
import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class AeroBackgroundService extends Service {
    public static final String ACTION_START = "com.aero5.mask.START";
    public static final String ACTION_STOP  = "com.aero5.mask.STOP";
    private static final String CHANNEL_ID  = "aero5_bg";
    private static final String TAG = "AERO5BG";
    private static final int    NOTIF_ID    = 2001;
    private static final long   SYNC_MS     = 30_000L; // 30s background sync

    private static final String WAQI_TOKEN = "4b32727b0be6942e1a3d537378d22c6b1ee8d5eb";
    private static final String ML_URL     = "https://aero5-ml.onrender.com/predict";

    private Handler handler;
    private String maskId = "";
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private final OkHttpClient client = new OkHttpClient();

    private static volatile int     latestAqi    = 0;
    private static volatile int     latestFilter = 0;
    private static volatile boolean alertsOn     = true;
    private double lastLat = 0, lastLon = 0;

    public static void updateData(int aqi, int filter){ latestAqi=aqi; latestFilter=filter; }
    public static void setAlertsEnabled(boolean on){ alertsOn=on; }

    @Override public void onCreate(){
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createChannel();
        setupLocationUpdates();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId){
        if(intent==null) return START_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){ 
            stopLocationUpdates();
            stopForeground(true); 
            stopSelf(); 
            return START_NOT_STICKY; 
        }
        maskId = intent.getStringExtra("maskId"); if(maskId==null) maskId="";
        
        Notification notification = buildNotif("AERO5 syncing in background");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        
        handler.removeCallbacks(syncTask);
        handler.post(syncTask);
        Log.d(TAG,"Service started: "+maskId);
        return START_STICKY;
    }

    private void setupLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 300000L) // 5 mins
                .setMinUpdateIntervalMillis(60000L) // 1 min
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    lastLat = location.getLatitude();
                    lastLon = location.getLongitude();
                    Log.d(TAG, "Location updated: " + lastLat + ", " + lastLon);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private final Runnable syncTask = new Runnable(){
        @Override public void run(){
            if(!maskId.isEmpty()) {
                if (lastLat != 0 && lastLon != 0) {
                    fetchAqiAndPredict();
                } else {
                    // Fallback to manual/last known if location not yet available
                    if (latestAqi > 0) pushFirebase();
                }
            }
            handler.postDelayed(this, SYNC_MS);
        }
    };

    private void fetchAqiAndPredict() {
        String waqiUrl = "https://api.waqi.info/feed/geo:" + lastLat + ";" + lastLon + "/?token=" + WAQI_TOKEN;
        Request request = new Request.Builder().url(waqiUrl).build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "WAQI fetch failed: " + e.getMessage());
                pushFirebase(); // Sync last known if API fails
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) return;
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    if ("ok".equals(json.getString("status"))) {
                        JSONObject data = json.getJSONObject("data");
                        int aqi = data.getInt("aqi");
                        double pm25 = data.getJSONObject("iaqi").optJSONObject("pm25") != null 
                            ? data.getJSONObject("iaqi").getJSONObject("pm25").getDouble("v") 
                            : aqi * 0.18; // Fallback derivation

                        latestAqi = aqi;
                        callML(aqi, pm25);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    pushFirebase();
                }
            }
        });
    }

    private void callML(int aqi, double pm25) {
        // Derive breathing rate in Java (same logic as JS)
        double pm25Clamped = Math.min(pm25, 300);
        int breathing = (int) Math.round(Math.max(12, Math.min(40, 12 + (pm25Clamped / 300) * 28)));
        int activity = 0; // Default to resting in background

        String mlUrl = ML_URL + "?aqi=" + aqi + "&breathing=" + breathing + "&activity=" + activity;
        Request request = new Request.Builder().url(mlUrl).build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                latestFilter = 72; // Fallback
                pushFirebase();
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        JSONObject data = new JSONObject(response.body().string());
                        latestFilter = (int) Math.round(Double.parseDouble(data.getString("effective_filtration").replace("%", "")));
                        pushFirebase();
                    } catch (Exception e) {
                        Log.e(TAG, "ML parse error: " + e.getMessage());
                        pushFirebase();
                    }
                }
            }
        });
    }

    private void pushFirebase(){
        try{
            DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("masks").child(maskId);
            Map<String,Object> d = new HashMap<>();
            d.put("aqi", latestAqi);
            d.put("filter", latestFilter);
            d.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).format(new Date()));
            d.put("source","android_bg_v2");
            ref.updateChildren(d)
               .addOnSuccessListener(v->{
                   Log.d(TAG,"Synced AQI="+latestAqi+" Filter="+latestFilter+"%");
                   updateNotif("AQI: "+latestAqi+" · Filter: "+latestFilter+"% · Background Active");
               })
               .addOnFailureListener(e->{
                   Log.e(TAG,"Firebase error: "+e.getMessage());
               });
        } catch(Exception e){ Log.e(TAG,"Push exception: "+e.getMessage()); }
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,"AERO5 Background Sync",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps AERO5 syncing live air quality data");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text){
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AERO5 Smart Mask")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotif(String text){
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotif(text));
        }
    }

    @Override public IBinder onBind(Intent i){ return null; }
    @Override public void onDestroy(){ 
        super.onDestroy(); 
        stopLocationUpdates();
        handler.removeCallbacks(syncTask); 
    }
}
