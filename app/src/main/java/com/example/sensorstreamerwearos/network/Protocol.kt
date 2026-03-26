package com.example.sensorstreamerwearos.network

object Protocol {
    const val VERSION = 2

    // Channel Paths (Legacy - kept for compatibility)
    const val PATH_SENSOR_STREAM = "/stream/sensors/v1"

    // Health Data Paths (New primary paths)
    const val PATH_HEALTH_DAILY = "/health/daily"     // ACTIVE: Daily summary every 15 min
    const val PATH_HEALTH_HR = "/health/hr"           // ACTIVE: HR batches when 5+ samples
    
    // On-Demand Measurement Requests (Phone → Watch)
    const val PATH_HEALTH_MEASURE_REQUEST = "/health/measure_request"  // Request immediate health data collection
    const val PATH_HEALTH_MEASURE_ACK = "/health/measure_ack"         // Watch acknowledgment
    
    // DEPRECATED: Unused paths - all metrics sent via PATH_HEALTH_DAILY
    @Deprecated("Not used - sleep data included in PATH_HEALTH_DAILY", ReplaceWith("PATH_HEALTH_DAILY"))
    const val PATH_HEALTH_SLEEP = "/health/sleep"
    @Deprecated("Not used - steps data included in PATH_HEALTH_DAILY", ReplaceWith("PATH_HEALTH_DAILY"))
    const val PATH_HEALTH_STEPS = "/health/steps"

    // Data API Paths (State Sync)
    const val PATH_WATCH_STATE = "/state/watch"
    const val PATH_PHONE_STATE = "/state/phone"

    // Keys for DataMap
    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_BUILD_CODE = "buildCode"
    const val KEY_STATE = "state"
    const val KEY_LATEST_LOCAL_SEQ = "latestLocalSeq"
    const val KEY_BACKLOG_COUNT = "backlogCount"
    const val KEY_LAST_RECEIVED_SEQ = "lastReceivedSeq"
    const val KEY_TIMESTAMP = "timestamp"

    // Capabilities
    const val CAPABILITY_PHONE_APP = "sensor_data_receiver"
    const val CAPABILITY_HEALTH_RECEIVER = "health_data_receiver"
}
