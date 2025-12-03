package com.example.sensorstreamerwearos.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;
import com.example.sensorstreamerwearos.MainActivity;
import com.example.sensorstreamerwearos.R;
import com.example.sensorstreamerwearos.model.SensorData;
import com.example.sensorstreamerwearos.network.WatchDataSender;
import com.example.sensorstreamerwearos.network.ConnectionManager;
import com.example.sensorstreamerwearos.sensor.HealthServicesManager;

public class HeartRateForegroundService extends LifecycleService implements ConnectionManager.ConnectionStateListener {

    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "HeartRateServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private HealthServicesManager healthServicesManager;
    private WatchDataSender watchDataSender;
    private final IBinder binder = new LocalBinder();
    private boolean isRunning = false;
    private boolean shouldBeRunning = false;

    public class LocalBinder extends Binder {
        public HeartRateForegroundService getService() {
            return HeartRateForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        healthServicesManager = new HealthServicesManager(this);
        watchDataSender = new WatchDataSender(this);
        
        healthServicesManager.setListener(data -> {
             if (ConnectionManager.INSTANCE.isVerified()) {
                 watchDataSender.sendSensorData(data);
             } else {
                 Log.w(TAG, "Data sampled but connection lost. Stopping & Re-handshaking.");
                 stopAccelerometerMonitoring();
                 watchDataSender.sendPing();
                 ConnectionManager.INSTANCE.setVerifying();
             }
        });
        
        ConnectionManager.INSTANCE.addListener(this);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        ConnectionManager.INSTANCE.removeListener(this);
    }

    @Override
    public void onStateChanged(ConnectionManager.State state) {
        Log.d(TAG, "onStateChanged: " + state);
        if (shouldBeRunning) {
            if (state == ConnectionManager.State.VERIFIED) {
                startAccelerometerMonitoring();
            } else {
                stopAccelerometerMonitoring();
                // If we should be running but lost connection, try to recover
                if (state == ConnectionManager.State.UNVERIFIED) {
                    Log.d(TAG, "Connection lost, attempting to re-handshake...");
                    watchDataSender.sendPing();
                    ConnectionManager.INSTANCE.setVerifying();
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "onStartCommand");

        if (intent != null) {
            String action = intent.getAction();
            if ("START".equals(action)) {
                startService();
            } else if ("STOP".equals(action)) {
                stopService();
            }
        }

        return START_STICKY;
    }

    private void startService() {
        if (isRunning) return;
        
        Log.d(TAG, "Starting Service");
        shouldBeRunning = true;
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Streamer")
                .setContentText("Streaming (Verified Only)")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Trigger check
        if (ConnectionManager.INSTANCE.isVerified()) {
            startAccelerometerMonitoring();
        } else {
            Log.d(TAG, "Service started but not verified. Requesting Handshake...");
            watchDataSender.sendPing();
            ConnectionManager.INSTANCE.setVerifying();
        }
        
        isRunning = true;
    }

    private void stopService() {
        Log.d(TAG, "Stopping Service");
        shouldBeRunning = false;
        
        stopAccelerometerMonitoring();

        stopForeground(true);
        stopSelf();
        isRunning = false;
    }
    
    private void startAccelerometerMonitoring() {
        Log.d(TAG, "Starting Accelerometer Monitoring");
        healthServicesManager.startAccelerometerMonitoring();
    }

    private void stopAccelerometerMonitoring() {
        Log.d(TAG, "Stopping Accelerometer Monitoring");
        healthServicesManager.stopAccelerometerMonitoring();
    }

    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Heart Rate Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
