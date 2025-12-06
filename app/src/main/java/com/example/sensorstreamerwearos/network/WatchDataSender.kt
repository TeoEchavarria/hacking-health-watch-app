package com.example.sensorstreamerwearos.network

import android.content.Context
import android.util.Log
import com.example.sensorstreamerwearos.model.SensorData
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WatchDataSender(private val context: Context) {

    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val scope = CoroutineScope(Dispatchers.IO)

    private suspend fun getBestNode(): String? {
        return try {
            val capabilityInfo = capabilityClient
                .getCapability("sensor_data_receiver", CapabilityClient.FILTER_REACHABLE)
                .await()
            
            val nodes = capabilityInfo.nodes
            if (nodes.isEmpty()) {
                Log.w(TAG, "⚠️ No nodes found with capability 'sensor_data_receiver'")
                // Fallback to connected nodes
                val connectedNodes = nodeClient.connectedNodes.await()
                if (connectedNodes.isEmpty()) {
                    Log.e(TAG, "❌ Absolutely no connected nodes found via NodeClient either")
                    null
                } else {
                    Log.w(TAG, "⚠️ Falling back to first connected node: ${connectedNodes[0].displayName}")
                    connectedNodes[0].id
                }
            } else {
                // Pick the nearest node (usually the phone)
                val bestNode = nodes.firstOrNull { it.isNearby } ?: nodes.first()
                Log.d(TAG, "✅ Found capable node: ${bestNode.displayName} (${bestNode.id})")
                bestNode.id
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding capable nodes", e)
            null
        }
    }

    fun sendSensorData(data: SensorData) {
        scope.launch {
            try {
                val nodeId = getBestNode()
                if (nodeId == null) return@launch

                val jsonString = Json.encodeToString(data)
                val byteArray = jsonString.toByteArray(Charsets.UTF_8)

                messageClient.sendMessage(nodeId, "/sensor_data", byteArray).await()
                Log.d(TAG, "✅ Sent sensor data to $nodeId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending sensor data", e)
            }
        }
    }

    fun sendBatch(batch: List<SensorData>) {
        scope.launch {
            try {
                Log.d(TAG, "📦 sendBatch called with ${batch.size} items")
                
                if (batch.isEmpty()) {
                    Log.w(TAG, "⚠️ Batch is empty, skipping send")
                    return@launch
                }

                val nodeId = getBestNode()
                if (nodeId == null) {
                    Log.e(TAG, "❌ Cannot send batch - no target node found")
                    return@launch
                }

                val jsonString = Json.encodeToString(batch)
                Log.d(TAG, "📤 Sending batch JSON (${jsonString.length} chars) to node $nodeId")
                val byteArray = jsonString.toByteArray(Charsets.UTF_8)

                // Log the attempt to send
                val summary = "count=${batch.size}, range=[${batch.first().timestamp}..${batch.last().timestamp}]"
                Log.d("ACCEL_WATCH_TO_PHONE", "Sending batch: $summary")

                val putDataMapReq = PutDataMapRequest.create("/sensor_batch")
                putDataMapReq.dataMap.putByteArray("batch_data", byteArray)
                putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())

                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()

                dataClient.putDataItem(putDataReq).await()
                Log.d(TAG, "✅ Sent batch of ${batch.size} items to /sensor_batch successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending batch", e)
            }
        }
    }
    
    fun sendPing() {
        scope.launch {
            try {
                Log.d(TAG, "🔍 Checking connectivity before sending PING...")
                
                val nodeId = getBestNode()
                if (nodeId == null) {
                    Log.e(TAG, "❌ Cannot send PING - no connected nodes")
                    return@launch
                }

                val putDataMapReq = PutDataMapRequest.create("/ping")
                putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()
                
                Log.d(TAG, "📤 Sending PING via DataClient...")
                dataClient.putDataItem(putDataReq).await()
                Log.d(TAG, "✅ PING sent successfully!")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending PING", e)
            }
        }
    }
    
    // TEST: Send accelerometer data using the same method as PING
    fun sendAccelTestData() {
        scope.launch {
            try {
                Log.d(TAG, "🧪 TEST: Sending accelerometer data like PING...")
                
                val nodeId = getBestNode()
                if (nodeId == null) {
                    Log.e(TAG, "❌ Cannot send accel test - no connected nodes")
                    return@launch
                }

                val putDataMapReq = PutDataMapRequest.create("/accel_test")
                putDataMapReq.dataMap.putString("accel_data", "x=1.0,y=2.0,z=3.0")
                putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()
                
                Log.d(TAG, "📤 Sending ACCEL TEST via DataClient...")
                dataClient.putDataItem(putDataReq).await()
                Log.d(TAG, "✅ ACCEL TEST sent successfully!")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending accel test", e)
            }
        }
    }

    companion object {
        private const val TAG = "WatchDataSender"
    }
}
