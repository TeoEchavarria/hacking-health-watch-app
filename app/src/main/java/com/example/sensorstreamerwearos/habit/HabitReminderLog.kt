package com.example.sensorstreamerwearos.habit

import kotlinx.serialization.Serializable

/**
 * Model for habit reminder log entries persisted on watch and synced to phone.
 */
@Serializable
data class HabitReminderLog(
    val logId: String,
    val habitId: String,
    val title: String,
    val triggeredAt: Long,
    val status: Status
) {
    @Serializable
    enum class Status {
        TRIGGERED,
        DONE,
        POSTPONED
    }
}
