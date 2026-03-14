package com.example.sensorstreamerwearos.network

object Protocol {
    const val VERSION = 2

    // Channel Paths (Legacy - kept for compatibility)
    const val PATH_SENSOR_STREAM = "/stream/sensors/v1"

    // Health Data Paths (New primary paths)
    const val PATH_HEALTH_DAILY = "/health/daily"
    const val PATH_HEALTH_HR = "/health/hr"
    const val PATH_HEALTH_SLEEP = "/health/sleep"
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
