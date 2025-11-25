package com.example.sensorstreamerwearos.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import com.example.sensorstreamerwearos.network.UdpRepository;
import com.example.sensorstreamerwearos.sensor.HealthServicesManager;

public class HeartRateForegroundService extends LifecycleService {

    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "HeartRateServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private HealthServicesManager healthServicesManager;
    private UdpRepository udpRepository;
    private final IBinder binder = new LocalBinder();
    private boolean isRunning = false;

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
        udpRepository = new UdpRepository();
        
        healthServicesManager.setListener(data -> {
            udpRepository.sendData(data);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "onStartCommand");

        if (intent != null) {
            String action = intent.getAction();
            if ("START".equals(action)) {
                String ip = intent.getStringExtra("IP");
                startService(ip);
            } else if ("STOP".equals(action)) {
                stopService();
            }
        }

        return START_STICKY;
    }

    private void startService(String ip) {
        if (isRunning) return;
        
        Log.d(TAG, "Starting Service with IP: " + ip);
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Streamer")
                .setContentText("Streaming Heart Rate Data...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        udpRepository.start(ip, 5005);
        healthServicesManager.startHeartRateMonitoring();
        isRunning = true;
    }

    private void stopService() {
        Log.d(TAG, "Stopping Service");
        healthServicesManager.stopHeartRateMonitoring();
        udpRepository.stop();
        stopForeground(true);
        stopSelf();
        isRunning = false;
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
