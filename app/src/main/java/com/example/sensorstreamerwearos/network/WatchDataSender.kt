package com.example.sensorstreamerwearos.network

import android.content.Context
import android.util.Log
import com.example.sensorstreamerwearos.model.SensorData
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
    private val scope = CoroutineScope(Dispatchers.IO)

    fun sendBatch(batch: List<SensorData>) {
        scope.launch {
            try {
                if (batch.isEmpty()) return@launch

                val jsonString = Json.encodeToString(batch)
                val byteArray = jsonString.toByteArray(Charsets.UTF_8)

                val putDataMapReq = PutDataMapRequest.create("/sensor_batch")
                putDataMapReq.dataMap.putByteArray("batch_data", byteArray)
                putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis()) // Ensure uniqueness

                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent() // Expedite delivery

                dataClient.putDataItem(putDataReq).await()
                Log.d("WatchDataSender", "Sent batch of ${batch.size} items to /sensor_batch")

            } catch (e: Exception) {
                Log.e("WatchDataSender", "Error sending batch", e)
            }
        }
    }
}
