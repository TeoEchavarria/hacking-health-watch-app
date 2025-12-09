package com.example.sensorstreamerwearos.network

import android.content.Context
import android.util.Log
import com.example.sensorstreamerwearos.data.SensorDao
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

class WatchConnectivityController(
    private val context: Context,
    private val sensorDao: SensorDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val channelClient = Wearable.getChannelClient(context)
    private val dataClient = Wearable.getDataClient(context)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _phoneNodeId = MutableStateFlow<String?>(null)

    private var currentChannel: ChannelClient.Channel? = null
    private var streamSender: ChannelStreamSender? = null
    
    // Last acknowledged sequence from phone
    private val _lastAckSeq = MutableStateFlow<Long>(0)
    val lastAckSeq: StateFlow<Long> = _lastAckSeq.asStateFlow()

    private val isStreaming = AtomicBoolean(false)

    init {
        monitorCapabilities()
        monitorPhoneState()
        startStatePublisher()
        startConnectionLoop()
    }

    private fun monitorCapabilities() {
        scope.launch {
            val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
                updateBestNode(info.nodes)
            }
            capabilityClient.addListener(capabilityListener, Protocol.CAPABILITY_PHONE_APP)

            // Initial check
            try {
                val info = capabilityClient.getCapability(
                    Protocol.CAPABILITY_PHONE_APP,
                    CapabilityClient.FILTER_REACHABLE
                ).await()
                updateBestNode(info.nodes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get capabilities", e)
            }
        }
    }

    private fun updateBestNode(nodes: Set<Node>) {
        val bestNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        _phoneNodeId.value = bestNode?.id
        if (bestNode == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
        } else if (_connectionState.value == ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.CONNECTING
        }
        Log.d(TAG, "Best node: ${bestNode?.id} (${bestNode?.displayName})")
    }

    private fun startConnectionLoop() {
        scope.launch {
            while (isActive) {
                val nodeId = _phoneNodeId.value
                if (nodeId != null && !isStreaming.get()) {
                    try {
                        connectAndStream(nodeId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Streaming failed, retrying in 5s...", e)
                        _connectionState.value = ConnectionState.CONNECTING
                        cleanupChannel()
                        delay(5000)
                    }
                } else {
                    delay(1000)
                }
            }
        }
    }

    private suspend fun connectAndStream(nodeId: String) {
        Log.d(TAG, "Opening channel to $nodeId...")
        val channel = channelClient.openChannel(nodeId, Protocol.PATH_SENSOR_STREAM).await()
        currentChannel = channel
        Log.d(TAG, "Channel opened: ${channel.path}")
        
        _connectionState.value = ConnectionState.STREAMING
        isStreaming.set(true)

        try {
            val outputStream = channelClient.getOutputStream(channel).await()
            streamSender = ChannelStreamSender(outputStream)
            
            // Backfill loop
            while (currentCoroutineContext().isActive && isStreaming.get()) {
                val ack = _lastAckSeq.value
                val batch = sensorDao.getPendingBatches(ack)
                
                if (batch.isEmpty()) {
                    // Live mode or idle
                    // For now, we just poll DB. In a real optimize scenario, we'd trigger on DB insert.
                    delay(500) 
                    continue
                }

                Log.d(TAG, "Sending batch of ${batch.size} items, starting from seq ${batch.first().seq}")
                for (item in batch) {
                    streamSender?.sendFrame(item)
                }
                
                // Small delay to prevent flooding if we have a huge backlog
                delay(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Channel error", e)
            throw e
        } finally {
            isStreaming.set(false)
            _connectionState.value = ConnectionState.CONNECTING
            cleanupChannel()
        }
    }

    private fun cleanupChannel() {
        try {
            currentChannel?.let { channelClient.close(it) }
            streamSender?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing channel", e)
        }
        currentChannel = null
        streamSender = null
    }

    private fun monitorPhoneState() {
        // Listen for data items from phone (ACKs)
        val listener = DataClient.OnDataChangedListener { events ->
            for (event in events) {
                if (event.type == DataEvent.TYPE_CHANGED && 
                    event.dataItem.uri.path == Protocol.PATH_PHONE_STATE) {
                    val item = DataMapItem.fromDataItem(event.dataItem)
                    val ack = item.dataMap.getLong(Protocol.KEY_LAST_RECEIVED_SEQ)
                    Log.d(TAG, "Received ACK from phone: $ack")
                    _lastAckSeq.value = ack
                    
                    // Cleanup local DB
                    scope.launch {
                        sensorDao.deleteAcknowledged(ack)
                    }
                }
            }
        }
        dataClient.addListener(listener)
    }

    private fun startStatePublisher() {
        scope.launch {
            combine(
                sensorDao.getMaxSeqFlow(),
                sensorDao.getBacklogCount(),
                _connectionState
            ) { maxSeq, backlog, state ->
                Triple(maxSeq ?: 0L, backlog, state)
            }.collect { (seq, backlog, state) ->
                publishState(seq, backlog, state)
                delay(10000) // Throttle updates
            }
        }
    }

    private suspend fun publishState(localSeq: Long, backlog: Int, state: ConnectionState) {
        try {
            val request = PutDataMapRequest.create(Protocol.PATH_WATCH_STATE).apply {
                dataMap.putLong(Protocol.KEY_PROTOCOL_VERSION, Protocol.VERSION.toLong())
                dataMap.putString(Protocol.KEY_STATE, state.name)
                dataMap.putLong(Protocol.KEY_LATEST_LOCAL_SEQ, localSeq)
                dataMap.putInt(Protocol.KEY_BACKLOG_COUNT, backlog)
                dataMap.putLong(Protocol.KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            
            dataClient.putDataItem(request).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish state", e)
        }
    }

    companion object {
        private const val TAG = "WatchConnectivity"
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        STREAMING,
        DEGRADED
    }
}
