package com.example.sensorstreamerwearos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.sensorstreamerwearos.MainActivity
import com.example.sensorstreamerwearos.R
import com.example.sensorstreamerwearos.health.HealthServicesDataSource
import com.example.sensorstreamerwearos.health.HealthSyncManager
import com.example.sensorstreamerwearos.network.ConnectionManager
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Foreground service for health data synchronization.
 * 
 * This service replaces SensorForegroundService and uses Health Services
 * instead of SensorManager for battery-efficient health data collection.
 * 
 * Responsibilities:
 * - Manages HealthSyncManager lifecycle
 * - Maintains foreground notification for visibility
 * - Handles watch-phone connection state
 * - Responds to phone ping/pong messages
 */
class HealthSyncService : LifecycleService(),
    ConnectionManager.ConnectionStateListener,
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "HealthSyncService"
        private const val CHANNEL_ID = "HealthSyncChannel"
        private const val NOTIFICATION_ID = 102
        
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_FORCE_SYNC = "FORCE_SYNC"
    }

    private var healthDataSource: HealthServicesDataSource? = null
    private var healthSyncManager: HealthSyncManager? = null
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    @Volatile
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== HealthSyncService CREATED ===")
        
        // Initialize health components
        healthDataSource = HealthServicesDataSource(this)
        healthSyncManager = HealthSyncManager(this, healthDataSource!!)
        
        // Register listeners
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        ConnectionManager.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HealthSyncService DESTROYED")
        
        ConnectionManager.removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        
        healthSyncManager?.stop()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> startSync()
            ACTION_STOP -> stopSync()
            ACTION_FORCE_SYNC -> forceSync()
            null -> {
                // Service restarted by system
                Log.d(TAG, "Service restarted by system")
                startSync()
            }
        }
        
        return START_STICKY
    }

    private fun startSync() {
        if (isRunning) {
            Log.d(TAG, "Already running, skipping start")
            return
        }
        
        Log.i(TAG, "=== STARTING HEALTH SYNC ===")
        isRunning = true
        
        createNotificationChannel()
        startForegroundWithNotification()
        
        // Start health sync manager
        healthSyncManager?.start()
        
        // Set connection to verifying and send ping
        ConnectionManager.setVerifying()
        sendPing()
        
        Log.i(TAG, "=== HEALTH SYNC STARTED ===")
    }

    private fun stopSync() {
        if (!isRunning) return
        
        Log.d(TAG, "Stopping health sync")
        isRunning = false
        
        healthSyncManager?.stop()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun forceSync() {
        serviceScope.launch {
            healthSyncManager?.forceSyncNow()
        }
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Health Sync Active")
            .setContentText("Monitoring health data")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Health Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background health data synchronization"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun sendPing() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(this@HealthSyncService)
                val messageClient = Wearable.getMessageClient(this@HealthSyncService)
                
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    val node = nodes.first()
                    messageClient.sendMessage(node.id, "/ping", "ping".toByteArray()).await()
                    Log.d(TAG, "Ping sent to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ping", e)
            }
        }
    }

    // ConnectionManager.ConnectionStateListener
    override fun onStateChanged(state: ConnectionManager.State) {
        Log.d(TAG, "Connection state: $state")
        
        if (state == ConnectionManager.State.VERIFIED) {
            // Connection verified, sync immediately
            serviceScope.launch {
                healthSyncManager?.forceSyncNow()
            }
        }
    }

    // DataClient.OnDataChangedListener
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                Log.d(TAG, "Data changed: $path")
                
                when (path) {
                    "/handshake_ack" -> {
                        Log.d(TAG, "✅ Handshake ACK received")
                        ConnectionManager.setVerified()
                    }
                }
            }
        }
    }

    // MessageClient.OnMessageReceivedListener
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.d(TAG, "Message received: $path")
        
        when (path) {
            "/pong" -> {
                Log.d(TAG, "🏓 PONG received - connection verified")
                ConnectionManager.setVerified()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
