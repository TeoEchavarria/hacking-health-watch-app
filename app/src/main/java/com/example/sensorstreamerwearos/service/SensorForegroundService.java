package com.example.sensorstreamerwearos.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;
import com.example.sensorstreamerwearos.MainActivity;
import com.example.sensorstreamerwearos.R;
import com.example.sensorstreamerwearos.model.SensorData;
import com.example.sensorstreamerwearos.network.WatchDataSender;
import com.example.sensorstreamerwearos.network.ConnectionManager;
import com.example.sensorstreamerwearos.sensor.HealthServicesManager;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.List;

public class SensorForegroundService extends LifecycleService implements ConnectionManager.ConnectionStateListener, DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private static final String TAG = "SensorForegroundService";
    private static final String CHANNEL_ID = "SensorServiceChannel";
    private static final int NOTIFICATION_ID = 101;
    
    // 1 minute for testing (change to 300000 for production = 5 min)
    private static final long BATCH_INTERVAL_MS = 60000;

    private HealthServicesManager healthServicesManager;
    private WatchDataSender watchDataSender;
    private final IBinder binder = new LocalBinder();
    private boolean isRunning = false;
    private boolean shouldBeRunning = false;
    
    private PowerManager.WakeLock wakeLock;
    
    // Data batching
    private final List<SensorData> dataBuffer = new ArrayList<>();
    private final Handler batchHandler = new Handler();
    private Runnable batchRunnable;

    public class LocalBinder extends Binder {
        public SensorForegroundService getService() {
            return SensorForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "=== WATCH_SERVICE_CREATED ===");
        
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorStreamer::BatchWakeLock");

        healthServicesManager = new HealthServicesManager(this);
        watchDataSender = new WatchDataSender(this);
        
        // Register Data Listener for persistent reception
        Wearable.getDataClient(this).addListener(this);
        Wearable.getMessageClient(this).addListener(this);
        
        healthServicesManager.setListener(data -> {
             // Only collect if we are intending to run
             if (shouldBeRunning) {
                 synchronized (dataBuffer) {
                     dataBuffer.add(data);
                     // Log every 10th sample to avoid spam
                     if (dataBuffer.size() % 10 == 0) {
                         Log.i(TAG, "WATCH_BUFFER_SIZE: " + dataBuffer.size() + " samples");
                     }
                 }
             }
        });
        
        ConnectionManager.INSTANCE.addListener(this);
        
        // Setup batch sending timer
        batchRunnable = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "=== BATCH_TIMER_FIRED ===");
                acquireWakeLock();
                try {
                    sendBatchData();
                } finally {
                    releaseWakeLock();
                }
                
                if (isRunning) {
                    batchHandler.postDelayed(this, BATCH_INTERVAL_MS);
                }
            }
        };
    }
    
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "onDataChanged: " + dataEvents.getCount() + " events");
        
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                String path = event.getDataItem().getUri().getPath();
                String nodeId = event.getDataItem().getUri().getHost();
                if (nodeId == null) nodeId = "unknown";
                
                Log.d(TAG, "Path: " + path);
                
                if (path.equals("/handshake_ack")) {
                    Log.d(TAG, "✅ Handshake ACK received! (In Persistent Service)");
                    ConnectionManager.INSTANCE.setVerified();
                }
            }
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        String nodeId = messageEvent.getSourceNodeId();
        Log.d(TAG, "📨 onMessageReceived: " + path + " from " + nodeId);
        
        if (path.equals("/pong")) {
            Log.d(TAG, "🏓 PONG Message received from phone! (In Persistent Service)");
            ConnectionManager.INSTANCE.setVerified();
        }
    }
    
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
        }
    }
    
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "WATCH_SERVICE_STOPPED");
        ConnectionManager.INSTANCE.removeListener(this);
        Wearable.getDataClient(this).removeListener(this);
        Wearable.getMessageClient(this).removeListener(this);
        batchHandler.removeCallbacks(batchRunnable);
        releaseWakeLock();
    }

    @Override
    public void onStateChanged(ConnectionManager.State state) {
        Log.d(TAG, "onStateChanged: " + state);
        if (shouldBeRunning) {
            if (state == ConnectionManager.State.VERIFIED) {
                // Connection restored
                startAccelerometerMonitoring();
            } else {
                // Connection lost
                // We keep monitoring, but we try to recover
                Log.w(TAG, "Connection lost/unverified. Attempting Ping...");
                watchDataSender.sendPing();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent == null) {
            Log.d(TAG, "WATCH_SERVICE_RESTARTED (system kill recovery)");
            startService();
        } else {
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
        if (isRunning) {
            Log.d(TAG, "Service already running, skipping start");
            return;
        }
        
        Log.i(TAG, "=== STARTING SENSOR SERVICE ===");
        shouldBeRunning = true;
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Streamer")
                .setContentText("Collecting Data in Background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startAccelerometerMonitoring();
        
        // Start batch timer - first batch after BATCH_INTERVAL_MS
        batchHandler.removeCallbacks(batchRunnable);
        batchHandler.postDelayed(batchRunnable, BATCH_INTERVAL_MS);
        Log.i(TAG, "Batch timer scheduled for " + (BATCH_INTERVAL_MS / 1000) + " seconds");
        
        // Initial handshake check
        Log.i(TAG, "Sending initial PING to phone...");
        watchDataSender.sendPing();
        ConnectionManager.INSTANCE.setVerifying();
        
        isRunning = true;
        Log.i(TAG, "=== SENSOR SERVICE STARTED SUCCESSFULLY ===");
    }

    private void stopService() {
        Log.d(TAG, "Stopping Sensor Service");
        shouldBeRunning = false;
        
        stopAccelerometerMonitoring();
        batchHandler.removeCallbacks(batchRunnable);

        stopForeground(true);
        stopSelf();
        isRunning = false;
    }
    
    private void sendBatchData() {
        Log.i(TAG, "=== WATCH_ACCEL_BATCH_ATTEMPT ===");
        
        List<SensorData> dataToSend;
        
        synchronized (dataBuffer) {
            Log.i(TAG, "Buffer contains " + dataBuffer.size() + " samples");
            if (dataBuffer.isEmpty()) {
                Log.d(TAG, "No data to send - buffer is empty");
                return;
            }
            
            // Create a copy and clear the buffer
            dataToSend = new ArrayList<>(dataBuffer);
            dataBuffer.clear();
        }
        
        Log.i(TAG, "=== SENDING BATCH: " + dataToSend.size() + " samples ===");
        watchDataSender.sendBatch(dataToSend);
    }
    
    private void startAccelerometerMonitoring() {
        Log.i(TAG, "WATCH_SENSOR_ACTIVE");
        healthServicesManager.startAccelerometerMonitoring();
    }

    private void stopAccelerometerMonitoring() {
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
                    "Sensor Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
