package com.example.sensorstreamerwearos.health

import kotlinx.serialization.Serializable

/**
 * A single heart rate measurement sample.
 */
@Serializable
data class HeartRateSample(
    val bpm: Int,
    val timestamp: Long,
    val accuracy: HeartRateAccuracy = HeartRateAccuracy.UNKNOWN
)

@Serializable
enum class HeartRateAccuracy {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Daily health summary containing aggregated metrics.
 * This is the primary data structure sent from watch to phone.
 */
@Serializable
data class HealthDailySummary(
    val date: String, // ISO date format: "2026-03-14"
    val steps: Int,
    val sleepMinutes: Int?,
    val heartRateSamples: List<HeartRateSample>,
    val avgHeartRate: Int?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val syncTimestamp: Long = System.currentTimeMillis()
)

/**
 * Incremental health update sent between full daily syncs.
 * Used for near real-time heart rate updates.
 */
@Serializable
data class HealthIncrementalUpdate(
    val type: UpdateType,
    val timestamp: Long,
    val heartRateSample: HeartRateSample? = null,
    val steps: Int? = null,
    val sleepMinutes: Int? = null
)

@Serializable
enum class UpdateType {
    HEART_RATE,
    STEPS,
    SLEEP
}
