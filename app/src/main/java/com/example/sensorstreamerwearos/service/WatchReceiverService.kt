package com.example.sensorstreamerwearos.service

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class WatchReceiverService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: ${dataEvents.count} events")
        
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                Log.d(TAG, "Path: $path")
                
                if (path == "/handshake_request") {
                    val nodeId = event.dataItem.uri.host ?: "unknown"
                    Log.d(TAG, "Handshake request received from $nodeId")
                    sendHandshakeResponse(nodeId)
                }
            }
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
