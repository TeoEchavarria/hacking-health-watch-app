package com.example.sensorstreamerwearos.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sensorstreamerwearos.R
import com.example.sensorstreamerwearos.network.ConnectionManager
import com.example.sensorstreamerwearos.network.Protocol
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Service that listens for Data Layer events from the phone app.
 * Handles:
 * - DATA_CHANGED: /handshake_ack, /state/phone, /handshake_request (legacy)
 * - MESSAGE_RECEIVED: /pong, /notification/test
 */
class WatchReceiverService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchReceiverService created")
        createNotificationChannel()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val nodeId = messageEvent.sourceNodeId
        Log.d(TAG, "📨 onMessageReceived: $path from $nodeId")
        
        when (path) {
            "/pong" -> {
                Log.d(TAG, "🏓 PONG Message received from phone! Connection Verified.")
                ConnectionManager.setVerified()
                ensureHealthSyncRunning()
            }
            "/notification/test" -> {
                val message = String(messageEvent.data)
                Log.d(TAG, "📱 Test notification received: $message")
                showConnectionTestNotification(message)
                ConnectionManager.setVerified()
                ensureHealthSyncRunning()
            }
            Protocol.PATH_HEALTH_MEASURE_REQUEST -> {
                val payload = String(messageEvent.data)
                Log.d(TAG, "💓 On-demand measurement request received: $payload")
                handleMeasurementRequest(nodeId, payload)
            }
        }
    }
    
    /**
     * Handle on-demand measurement request from phone.
     * Triggers immediate health data collection and sync.
     */
    private fun handleMeasurementRequest(nodeId: String, payload: String) {
        scope.launch {
            try {
                Log.i(TAG, "[MEASURE_REQUEST] Triggering immediate health data sync")
                
                // Trigger immediate health sync
                val intent = Intent(this@WatchReceiverService, HealthSyncService::class.java).apply {
                    action = HealthSyncService.ACTION_FORCE_SYNC
                }
                androidx.core.content.ContextCompat.startForegroundService(this@WatchReceiverService, intent)
                
                // Send acknowledgment to phone
                val messageClient = Wearable.getMessageClient(this@WatchReceiverService)
                messageClient.sendMessage(
                    nodeId,
                    Protocol.PATH_HEALTH_MEASURE_ACK,
                    "Measurement started".toByteArray()
                ).await()
                
                Log.i(TAG, "[MEASURE_REQUEST] ✅ Immediate sync triggered and ACK sent")
                
            } catch (e: Exception) {
                Log.e(TAG, "[MEASURE_REQUEST] ❌ Failed to handle measurement request", e)
            }
        }
    }
    
    /**
     * Ensure HealthSyncService is running when connection is verified.
     * This starts health data collection and sync to phone.
     */
    private fun ensureHealthSyncRunning() {
        try {
            Log.i(TAG, "Starting HealthSyncService from WatchReceiverService")
            Log.i(TAG, "[DIAGNOSTIC][RECEIVER] Pong/connection verified - starting HealthSyncService")
            val intent = Intent(this, HealthSyncService::class.java).apply {
                action = HealthSyncService.ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            Log.i(TAG, "[DIAGNOSTIC][RECEIVER][SUCCESS] HealthSyncService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HealthSyncService", e)
            Log.e(TAG, "[DIAGNOSTIC][RECEIVER][ERROR] Failed to start HealthSyncService: ${e.message}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: ${dataEvents.count} events")
        
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                val nodeId = event.dataItem.uri.host ?: "unknown"
                Log.d(TAG, "Path: $path from $nodeId")
                
                when (path) {
                    "/handshake_request" -> {
                        // Legacy: Phone requests handshake
                        Log.d(TAG, "Legacy handshake request received from $nodeId")
                        sendHandshakeResponse(nodeId)
                    }
                    "/handshake_ack" -> {
                        // Phone acknowledged our handshake
                        Log.d(TAG, "✅ Handshake ACK received from phone! Connection Verified.")
                        ConnectionManager.setVerified()
                    }
                    Protocol.PATH_PHONE_STATE -> {
                        // Phone published state with ACK
                        handlePhoneState(event)
                    }
                }
            }
        }
    }
    
    private fun handlePhoneState(event: DataEvent) {
        try {
            val item = DataMapItem.fromDataItem(event.dataItem)
            val state = item.dataMap.getString(Protocol.KEY_STATE)
            val lastReceivedSeq = item.dataMap.getLong(Protocol.KEY_LAST_RECEIVED_SEQ)
            Log.d(TAG, "Phone state: $state, lastReceivedSeq: $lastReceivedSeq")
            
            if (state == "READY" || lastReceivedSeq > 0) {
                Log.d(TAG, "✅ Phone is READY - connection verified")
                ConnectionManager.setVerified()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling phone state", e)
        }
    }

    private fun sendHandshakeResponse(nodeId: String) {
        scope.launch {
            try {
                val json = JSONObject()
                json.put("type", "handshake")
                json.put("source", "watch")
                json.put("timestamp", System.currentTimeMillis())
                
                val dataClient = Wearable.getDataClient(this@WatchReceiverService)
                val putDataMapReq = PutDataMapRequest.create("/handshake_response")
                putDataMapReq.dataMap.putString("payload", json.toString())
                putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis()) // For uniqueness
                
                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()
                
                dataClient.putDataItem(putDataReq).await()
                Log.d(TAG, "Handshake response sent: $json")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending handshake response", e)
            }
        }
    }

    companion object {
        private const val TAG = "WatchReceiverService"
        private const val CHANNEL_ID = "connection_test_channel"
        private const val NOTIFICATION_ID = 9001
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection Test",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for connection tests from phone"
            enableVibration(true)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun showConnectionTestNotification(message: String) {
        // Vibrate to get user attention
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        
        // Show notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Phone Connected")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        Log.d(TAG, "Notification shown: $message")
    }
}
