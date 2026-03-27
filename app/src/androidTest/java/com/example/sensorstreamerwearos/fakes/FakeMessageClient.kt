package com.example.sensorstreamerwearos.fakes

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple fake for MessageClient for watch tests.
 */
class FakeMessageClient(
    private val defaultLatencyMs: Long = 50L,
    private val failureRate: Float = 0.0f
) {
    
    private val listeners = mutableListOf<MessageClient.OnMessageReceivedListener>()
    private val sentMessages = ConcurrentHashMap<String, MutableList<ByteArray>>()
    
    var isConnected = true
    var latencyMs = defaultLatencyMs
    
    /**
     * Simulate receiving a message from the phone
     */
    suspend fun simulateReceive(path: String, data: ByteArray, sourceNodeId: String = "phone-node") {
        if (!isConnected) return
        
        delay(latencyMs)
        
        val event = object : MessageEvent {
            override fun getPath(): String = path
            override fun getData(): ByteArray = data
            override fun getSourceNodeId(): String = sourceNodeId
            override fun getRequestId(): Int = 0
        }
        
        listeners.forEach { it.onMessageReceived(event) }
    }
    
    /**
     * Get all messages sent to a specific path
     */
    fun getMessagesSentTo(path: String): List<ByteArray> {
        return sentMessages[path] ?: emptyList()
    }
    
    /**
     * Clear sent message history
     */
    fun clearSentMessages() {
        sentMessages.clear()
    }
    
    /**
     * Simplified sendMessage for testing
     */
    fun sendMessage(nodeId: String, path: String, data: ByteArray?): Task<Int> {
        return if (isConnected && Math.random() > failureRate) {
            sentMessages.getOrPut(path) { mutableListOf() }.add(data ?: byteArrayOf())
            Tasks.forResult(data?.size ?: 0)
        } else {
            Tasks.forException(Exception("Failed to send message"))
        }
    }
    
    /**
     * Add message listener
     */
    fun addListener(listener: MessageClient.OnMessageReceivedListener): Task<Void> {
        listeners.add(listener)
        return Tasks.forResult(null)
    }
    
    /**
     * Remove message listener
     */
    fun removeListener(listener: MessageClient.OnMessageReceivedListener): Task<Boolean> {
        val removed = listeners.remove(listener)
        return Tasks.forResult(removed)
    }
    
    /**
     * Get number of registered listeners
     */
    fun getListenerCount(): Int = listeners.size
}
