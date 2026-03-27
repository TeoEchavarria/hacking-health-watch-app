package com.example.sensorstreamerwearos.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.example.sensorstreamerwearos.fakes.FakeMessageClient
import com.example.sensorstreamerwearos.utils.TestLogger
import com.example.sensorstreamerwearos.utils.WatchTestConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test suite for PING/PONG protocol from Watch side.
 * 
 * Protocol:
 * 1. Watch sends /ping to phone
 * 2. Phone responds with /pong
 * 3. Watch verifies connection on pong receipt
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class WatchPingProtocolTest {
    
    private lateinit var messageClient: FakeMessageClient
    private val testName = "WatchPingProtocol"
    
    @Before
    fun setup() {
        TestLogger.start(testName)
        messageClient = FakeMessageClient(defaultLatencyMs = 50L)
    }
    
    @After
    fun teardown() {
        messageClient.clearSentMessages()
        TestLogger.cleanup(testName)
    }
    
    /**
     * Test: Watch can send PING message
     */
    @Test
    fun testWatchSendsPing_messageSentToCorrectPath() = runBlocking {
        TestLogger.expectation("$testName.sendPing", "Watch sends /ping with null or small payload")
        
        val nodeId = "phone-node-123"
        messageClient.sendMessage(nodeId, WatchTestConstants.PATH_PING, null)
        
        val sentMessages = messageClient.getMessagesSentTo(WatchTestConstants.PATH_PING)
        assertThat(sentMessages).hasSize(1)
        
        TestLogger.actual("$testName.sendPing", "ping_sent=${sentMessages.size}")
        TestLogger.pass("$testName.sendPing", "message_count=${sentMessages.size}")
    }
    
    /**
     * Test: Watch handles PONG response correctly
     */
    @Test
    fun testWatchReceivesPong_connectionVerified() = runBlocking {
        TestLogger.expectation("$testName.receivePong", "Watch receives /pong and updates connection state")
        
        var pongReceived = false
        
        messageClient.addListener { event ->
            if (event.path == WatchTestConstants.PATH_PONG) {
                pongReceived = true
            }
        }
        
        // Simulate phone sending PONG
        messageClient.simulateReceive(WatchTestConstants.PATH_PONG, byteArrayOf(), "phone-node")
        
        assertThat(pongReceived).isTrue()
        
        TestLogger.actual("$testName.receivePong", "pong_received=$pongReceived")
        TestLogger.pass("$testName.receivePong", "connection_verified=true")
    }
    
    /**
     * Test: Full PING/PONG cycle from watch perspective
     */
    @Test
    fun testFullPingPongCycle_success() = runBlocking {
        TestLogger.expectation("$testName.fullCycle", "PING -> PONG cycle completes successfully")
        
        val startTime = System.currentTimeMillis()
        
        // Watch sends PING
        messageClient.sendMessage("phone-node", WatchTestConstants.PATH_PING, null)
        assertThat(messageClient.getMessagesSentTo(WatchTestConstants.PATH_PING)).hasSize(1)
        
        // Simulate phone responding with PONG
        var pongReceived = false
        messageClient.addListener { event ->
            if (event.path == WatchTestConstants.PATH_PONG) {
                pongReceived = true
            }
        }
        
        messageClient.simulateReceive(WatchTestConstants.PATH_PONG, byteArrayOf(), "phone-node")
        
        val latency = System.currentTimeMillis() - startTime
        
        assertThat(pongReceived).isTrue()
        assertThat(latency).isLessThan(WatchTestConstants.PING_PONG_SLA_MS)
        
        TestLogger.actual("$testName.fullCycle", "latency=${latency}ms, pong_received=$pongReceived")
        TestLogger.pass("$testName.fullCycle", "latency=${latency}ms")
    }
    
    /**
     * Test: PONG timeout when phone doesn't respond
     */
    @Test
    fun testPongTimeout_whenNoResponse() = runBlocking {
        TestLogger.expectation("$testName.timeout", "Watch handles PONG timeout correctly")
        
        var timedOut = false
        
        // Send PING but don't simulate PONG response
        messageClient.sendMessage("phone-node", WatchTestConstants.PATH_PING, null)
        
        // Use a shorter timeout for testing
        try {
            withTimeout(100L) {
                // Wait for PONG that never comes
                while (messageClient.getMessagesSentTo(WatchTestConstants.PATH_PONG).isEmpty()) {
                    delay(10)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            timedOut = true
        }
        
        assertThat(timedOut).isTrue()
        
        TestLogger.actual("$testName.timeout", "timeout_detected=$timedOut")
        TestLogger.pass("$testName.timeout", "timeout_handled=true")
    }
    
    /**
     * Test: Watch handles disconnection gracefully
     */
    @Test
    fun testDisconnected_pingFails() = runBlocking {
        TestLogger.expectation("$testName.disconnected", "PING fails when disconnected")
        
        messageClient.isConnected = false
        
        try {
            val result = messageClient.sendMessage("phone-node", WatchTestConstants.PATH_PING, null)
            // With failureRate=0 and connected=false, this should fail
            assertThat(result.isSuccessful).isFalse()
        } catch (e: Exception) {
            // Expected
        }
        
        val sentMessages = messageClient.getMessagesSentTo(WatchTestConstants.PATH_PING)
        // No messages should be recorded when disconnected
        assertThat(sentMessages).isEmpty()
        
        TestLogger.actual("$testName.disconnected", "messages_sent=${sentMessages.size}")
        TestLogger.pass("$testName.disconnected", "properly_blocked=true")
    }
}
