package com.example.sensorstreamerwearos.network

import android.content.Context
import android.util.Log
import com.example.sensorstreamerwearos.model.SensorData
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

class WatchConnectionManager(private val context: Context) : MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "WatchConnectionManager"
        private const val CAPABILITY_PHONE_APP = "sensor_data_receiver"
        
        // Paths
        private const val PATH_HANDSHAKE_START = "/handshake_start"
        private const val PATH_HANDSHAKE_ACK = "/handshake_ack"
        private const val PATH_SENSOR_STREAM = "/sensor_stream"
        private const val PATH_PING = "/ping"
        private const val PATH_PONG = "/pong"

        // Timing
        private const val HANDSHAKE_RETRY_DELAY_MS = 3000L
        private const val MAX_HANDSHAKE_RETRIES = 5
        private const val PING_INTERVAL_MS = 10000L // 10 seconds
        private const val PONG_TIMEOUT_MS = 5000L   // 5 seconds
    }

    enum class ConnectionState {
        DISCONNECTED,
        HANDSHAKING,
        VERIFIED
    }

    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var currentState = ConnectionState.DISCONNECTED
    
    @Volatile
    private var targetNodeId: String? = null

    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    
    // Listener for state changes (optional, for UI/Service updates)
    var onStateChange: ((ConnectionState) -> Unit)? = null

    init {
        messageClient.addListener(this)
    }

    fun startConnection() {
        Log.d(TAG, "Starting connection process...")
        scope.launch {
            findNodeAndHandshake()
        }
    }

    fun stopConnection() {
        Log.d(TAG, "Stopping connection process...")
        currentState = ConnectionState.DISCONNECTED
        stopPingCycle()
        targetNodeId = null
        notifyStateChange()
    }

    fun sendSensorData(data: SensorData) {
        if (currentState != ConnectionState.VERIFIED) {
            // Log.v(TAG, "Skipping data send - not VERIFIED (State: $currentState)")
            return
        }

        val nodeId = targetNodeId
        if (nodeId == null) {
            Log.e(TAG, "Cannot send data - targetNodeId is null")
            resetConnection()
            return
        }

        scope.launch {
            try {
                val jsonString = Json.encodeToString(data)
                val byteArray = jsonString.toByteArray(Charsets.UTF_8)
                messageClient.sendMessage(nodeId, PATH_SENSOR_STREAM, byteArray).await()
                // Log.v(TAG, "Sent sensor data sample")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send sensor data", e)
                // If sending fails, we might have lost connection
                resetConnection()
            }
        }
    }

    private suspend fun findNodeAndHandshake() {
        currentState = ConnectionState.HANDSHAKING
        notifyStateChange()

        var retries = 0
        while (retries < MAX_HANDSHAKE_RETRIES && currentState == ConnectionState.HANDSHAKING) {
            try {
                val nodeId = getBestNode()
                if (nodeId != null) {
                    targetNodeId = nodeId
                    Log.d(TAG, "Attempting handshake with node: $nodeId (Attempt ${retries + 1})")
                    
                    // Send Handshake
                    messageClient.sendMessage(nodeId, PATH_HANDSHAKE_START, "START".toByteArray()).await()
                    
                    // Wait for ACK is handled in onMessageReceived
                    // We just pause here before retry
                    delay(HANDSHAKE_RETRY_DELAY_MS)
                    
                    if (currentState == ConnectionState.VERIFIED) {
                        Log.d(TAG, "Handshake successful!")
                        return
                    }
                } else {
                    Log.w(TAG, "No capable node found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handshake attempt failed", e)
            }
            
            retries++
            delay(1000)
        }
        
        if (currentState != ConnectionState.VERIFIED) {
            Log.e(TAG, "Handshake failed after $MAX_HANDSHAKE_RETRIES attempts")
            currentState = ConnectionState.DISCONNECTED
            notifyStateChange()
            
            // Optional: keep retrying indefinitely or stop? 
            // Prompt says "Retry handshake" on failure.
            // We'll wait a bit longer and try again if still "active" in user's mind,
            // but here we might just stop to avoid battery drain loop if phone is gone.
            // For now, let's just log.
        }
    }
    
    private suspend fun getBestNode(): String? {
        return try {
            val capabilityInfo = capabilityClient
                .getCapability(CAPABILITY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
                .await()
            
            val nodes = capabilityInfo.nodes
            if (nodes.isNotEmpty()) {
                nodes.firstOrNull { it.isNearby }?.id ?: nodes.first().id
            } else {
                // Fallback to any connected node
                val connectedNodes = nodeClient.connectedNodes.await()
                connectedNodes.firstOrNull()?.id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nodes", e)
            null
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val sourceNodeId = messageEvent.sourceNodeId
        
        when (path) {
            PATH_HANDSHAKE_ACK -> {
                Log.d(TAG, "Received HANDSHAKE_ACK from $sourceNodeId")
                if (currentState == ConnectionState.HANDSHAKING) {
                    targetNodeId = sourceNodeId // Confirm target
                    currentState = ConnectionState.VERIFIED
                    notifyStateChange()
                    startPingCycle()
                }
            }
            PATH_PONG -> {
                Log.d(TAG, "Received PONG from $sourceNodeId")
                if (currentState == ConnectionState.VERIFIED) {
                    // Cancel timeout job as we received pong
                    pongTimeoutJob?.cancel()
                }
            }
        }
    }

    private fun startPingCycle() {
        stopPingCycle()
        pingJob = scope.launch {
            while (currentState == ConnectionState.VERIFIED && isActive) {
                sendPing()
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun stopPingCycle() {
        pingJob?.cancel()
        pongTimeoutJob?.cancel()
    }

    private suspend fun sendPing() {
        val nodeId = targetNodeId ?: return
        try {
            Log.d(TAG, "Sending PING")
            messageClient.sendMessage(nodeId, PATH_PING, null).await()
            
            // Start timeout check
            pongTimeoutJob?.cancel()
            pongTimeoutJob = scope.launch {
                delay(PONG_TIMEOUT_MS)
                Log.e(TAG, "PONG timeout! Connection lost.")
                resetConnection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send PING", e)
            resetConnection()
        }
    }

    private fun resetConnection() {
        if (currentState == ConnectionState.DISCONNECTED) return
        
        Log.w(TAG, "Resetting connection due to failure...")
        currentState = ConnectionState.DISCONNECTED
        stopPingCycle()
        notifyStateChange()
        
        // Auto-retry logic: Re-enter PHASE 1 (handshake again)
        scope.launch {
            delay(1000)
            startConnection()
        }
    }

    private fun notifyStateChange() {
        onStateChange?.invoke(currentState)
    }

    fun destroy() {
        messageClient.removeListener(this)
        scope.cancel()
    }
}
