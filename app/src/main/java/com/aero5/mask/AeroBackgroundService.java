package com.aero5.mask;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AeroBackgroundService extends Service {

    public static final String ACTION_START = "com.aero5.mask.START";
    public static final String ACTION_STOP  = "com.aero5.mask.STOP";
    private static final String CHANNEL_ID  = "aero5_sync";
    private static final String TAG = "AERO5BG";
    private static final int NOTIF_ID = 1001;
    private static final long SYNC_INTERVAL = 15_000L; // 15 seconds

    private Handler handler;
    private String maskId = "";

    // Shared mutable state updated by bridge
    private static volatile int latestAqi = 0;
    private static volatile int latestFilter = 0;

    public static void updateData(int aqi, int filter) {
        latestAqi = aqi;
        latestFilter = filter;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        maskId = intent.getStringExtra("maskId");
        if (maskId == null) maskId = "";

        startForeground(NOTIF_ID, buildNotification("🟢 AERO5 syncing in background..."));
        scheduleSync();
        Log.d(TAG, "Background service started for mask: " + maskId);
        return START_STICKY;
    }

    private void scheduleSync() {
        handler.post(syncRunnable);
    }

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!maskId.isEmpty() && latestAqi > 0) {
                syncToFirebase();
            }
            handler.postDelayed(this, SYNC_INTERVAL);
        }
    };

    private void syncToFirebase() {
        try {
            DatabaseReference ref = FirebaseDatabase.getInstance().getReference("masks").child(maskId);
            Map<String, Object> data = new HashMap<>();
            data.put("aqi", latestAqi);
            data.put("filter", latestFilter);
            data.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
            data.put("source", "android_bg");

            ref.updateChildren(data)
               .addOnSuccessListener(aVoid -> {
                   Log.d(TAG, "✅ Firebase sync: AQI=" + latestAqi + " Filter=" + latestFilter + "%");
                   updateNotification("✅ AQI: " + latestAqi + " — Filter: " + latestFilter + "% synced");
               })
               .addOnFailureListener(e -> {
                   Log.e(TAG, "❌ Firebase error: " + e.getMessage());
                   updateNotification("⚠️ Sync error — retrying...");
               });
        } catch (Exception e) {
            Log.e(TAG, "Sync exception: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "AERO5 Background Sync",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Keeps AERO5 syncing air quality data");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AERO5 Smart Mask")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(syncRunnable);
        Log.d(TAG, "Background service destroyed");
    }
}
