package com.example.sensorstreamerwearos.habit

/**
 * Model for habit reminder log entries persisted on watch and synced to phone.
 */
data class HabitReminderLog(
    val logId: String,
    val habitId: String,
    val title: String,
    val triggeredAt: Long,
    val status: Status
) {
    enum class Status {
        TRIGGERED,
        DONE,
        POSTPONED
    }
}
