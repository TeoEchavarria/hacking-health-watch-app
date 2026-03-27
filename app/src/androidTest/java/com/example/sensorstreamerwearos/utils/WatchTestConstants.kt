package com.example.sensorstreamerwearos.utils

/**
 * Constants for testing Watch side of Wearable Data Layer communication
 */
object WatchTestConstants {
    // Same paths as phone for consistency
    const val PATH_PING = "/ping"
    const val PATH_PONG = "/pong"
    const val PATH_WORKOUT_START = "/workout/start"
    const val PATH_WORKOUT_ACK = "/workout/ack"
    const val PATH_HEALTH_DAILY = "/health/daily"
    const val PATH_HEALTH_HR = "/health/hr"
    
    // Timeout values (milliseconds)
    const val TIMEOUT_ACK = 500L
    const val TIMEOUT_DEFAULT = 5000L
    const val PING_PONG_SLA_MS = 200L // Max latency for PING/PONG cycle
    const val PONG_TIMEOUT_MS = 5000L // Max wait for PONG response
    
    // Test data
    const val TEST_ROUTINE_ID = "test-routine-uuid-12345"
    const val TEST_SESSION_ID = "test-session-uuid-67890"
    const val TEST_DEVICE_ID = "test-watch-001"
    
    // Protocol version
    const val PROTOCOL_VERSION = 2
    
    // Health data collection intervals
    const val HEALTH_SYNC_INTERVAL_MS = 900000L // 15 minutes
    const val HR_SAMPLE_INTERVAL_MS = 60000L // 1 minute
}
