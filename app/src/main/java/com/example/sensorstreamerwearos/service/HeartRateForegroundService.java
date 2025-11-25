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
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;
import com.example.sensorstreamerwearos.MainActivity;
import com.example.sensorstreamerwearos.R;
import com.example.sensorstreamerwearos.model.SensorData;
import com.example.sensorstreamerwearos.network.UdpRepository;
import com.example.sensorstreamerwearos.sensor.HealthServicesManager;
import java.util.ArrayList;
import java.util.List;

public class HeartRateForegroundService extends LifecycleService {

    private static final String TAG = "HeartRateService";
    private static final String CHANNEL_ID = "HeartRateServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    
    // CONFIGURABLE: Change this value to adjust batch interval (in milliseconds)
    // 30 seconds = 30000, 1 minute = 60000, 2 hours = 7200000
    private static final long BATCH_INTERVAL_MS = 300000; // 5 minutes

    private HealthServicesManager healthServicesManager;
    private UdpRepository udpRepository;
    private final IBinder binder = new LocalBinder();
    private boolean isRunning = false;
    
    // Data batching
    private final List<SensorData> dataBuffer = new ArrayList<>();
    private final Handler batchHandler = new Handler();
    private Runnable batchRunnable;

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
        
        // Add data to buffer instead of sending immediately
        healthServicesManager.setListener(data -> {
            synchronized (dataBuffer) {
                dataBuffer.add(data);
            }
        });
        
        // Setup batch sending timer
        batchRunnable = new Runnable() {
            @Override
            public void run() {
                sendBatchData();
                if (isRunning) {
                    batchHandler.postDelayed(this, BATCH_INTERVAL_MS);
                }
            }
        };
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

        int intervalSeconds = (int) (BATCH_INTERVAL_MS / 1000);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Streamer")
                .setContentText("Batching data (sending every " + intervalSeconds + "s)")
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
        healthServicesManager.startAccelerometerMonitoring();
        
        // Start batch sending timer
        batchHandler.postDelayed(batchRunnable, BATCH_INTERVAL_MS);
        
        isRunning = true;
    }

    private void stopService() {
        Log.d(TAG, "Stopping Service");
        
        // Stop batch timer
        batchHandler.removeCallbacks(batchRunnable);
        
        // Send any remaining data before stopping
        sendBatchData();
        
        healthServicesManager.stopAccelerometerMonitoring();
        udpRepository.stop();
        stopForeground(true);
        stopSelf();
        isRunning = false;
    }
    
    private void sendBatchData() {
        List<SensorData> dataToSend;
        
        synchronized (dataBuffer) {
            if (dataBuffer.isEmpty()) {
                Log.d(TAG, "No data to send");
                return;
            }
            
            // Create a copy and clear the buffer
            dataToSend = new ArrayList<>(dataBuffer);
            dataBuffer.clear();
        }
        
        Log.d(TAG, "Sending batch of " + dataToSend.size() + " samples");
        
        // Send each data point
        for (SensorData data : dataToSend) {
            udpRepository.sendData(data);
        }
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
