package com.example.sensorstreamerwearos.service

import android.util.Log
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
 * - MESSAGE_RECEIVED: /pong
 */
class WatchReceiverService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchReceiverService created")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val nodeId = messageEvent.sourceNodeId
        Log.d(TAG, "📨 onMessageReceived: $path from $nodeId")
        
        when (path) {
            "/pong" -> {
                Log.d(TAG, "🏓 PONG Message received from phone! Connection Verified.")
                ConnectionManager.setVerified()
            }
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
    }
}
