package com.aero5.mask;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class AeroBackgroundService extends Service {
    public static final String ACTION_START = "com.aero5.mask.START";
    public static final String ACTION_STOP  = "com.aero5.mask.STOP";
    private static final String CHANNEL_ID  = "aero5_bg";
    private static final String TAG = "AERO5BG";
    private static final int    NOTIF_ID    = 2001;
    private static final long   SYNC_MS     = 15_000L;

    private Handler handler;
    private String maskId = "";

    private static volatile int     latestAqi    = 0;
    private static volatile int     latestFilter = 0;
    private static volatile boolean alertsOn     = true;

    public static void updateData(int aqi, int filter){ latestAqi=aqi; latestFilter=filter; }
    public static void setAlertsEnabled(boolean on){ alertsOn=on; }

    @Override public void onCreate(){
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId){
        if(intent==null) return START_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){ stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        maskId = intent.getStringExtra("maskId"); if(maskId==null) maskId="";
        startForeground(NOTIF_ID, buildNotif("AERO5 syncing in background"));
        handler.post(syncTask);
        Log.d(TAG,"Service started: "+maskId);
        return START_STICKY;
    }

    private final Runnable syncTask = new Runnable(){
        @Override public void run(){
            if(!maskId.isEmpty() && latestAqi>0) pushFirebase();
            handler.postDelayed(this, SYNC_MS);
        }
    };

    private void pushFirebase(){
        try{
            DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("masks").child(maskId);
            Map<String,Object> d = new HashMap<>();
            d.put("aqi", latestAqi);
            d.put("filter", latestFilter);
            d.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).format(new Date()));
            d.put("source","android_bg");
            ref.updateChildren(d)
               .addOnSuccessListener(v->{
                   Log.d(TAG,"Synced AQI="+latestAqi+" Filter="+latestFilter+"%");
                   updateNotif("AQI: "+latestAqi+" · Filter: "+latestFilter+"% · Synced");
               })
               .addOnFailureListener(e->{
                   Log.e(TAG,"Firebase error: "+e.getMessage());
                   updateNotif("Sync error — retrying...");
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
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotif(text));
    }

    @Override public IBinder onBind(Intent i){ return null; }
    @Override public void onDestroy(){ super.onDestroy(); handler.removeCallbacks(syncTask); }
}
