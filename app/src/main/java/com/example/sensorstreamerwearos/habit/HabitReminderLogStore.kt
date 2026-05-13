package com.example.sensorstreamerwearos.habit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Persists HabitReminderLog entries on the watch.
 * Keeps last 100 logs to avoid unbounded growth.
 */
object HabitReminderLogStore {
    private val Context.habitLogDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "habit_reminder_logs"
    )

    private val LOGS_KEY = stringPreferencesKey("habit_reminder_logs")
    private val CURRENT_LOG_ID_KEY = stringPreferencesKey("current_log_id")
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_LOGS = 100

    suspend fun appendLog(context: Context, log: HabitReminderLog): HabitReminderLog {
        context.habitLogDataStore.edit { prefs ->
            val current = prefs[LOGS_KEY]?.let { json.decodeFromString<List<HabitReminderLog>>(it) } ?: emptyList()
            val updated = (listOf(log) + current).take(MAX_LOGS)
            prefs[LOGS_KEY] = json.encodeToString(updated)
            prefs[CURRENT_LOG_ID_KEY] = log.logId
        }
        return log
    }

    suspend fun updateLogStatus(context: Context, logId: String, status: HabitReminderLog.Status): HabitReminderLog? {
        var updated: HabitReminderLog? = null
        context.habitLogDataStore.edit { prefs ->
            val current = prefs[LOGS_KEY]?.let { json.decodeFromString<List<HabitReminderLog>>(it) } ?: emptyList()
            val list = current.map {
                if (it.logId == logId) {
                    updated = it.copy(status = status)
                    updated!!
                } else it
            }
            if (updated != null) {
                prefs[LOGS_KEY] = json.encodeToString(list)
            }
            prefs.remove(CURRENT_LOG_ID_KEY)
        }
        return updated
    }

    suspend fun getCurrentLogId(context: Context): String? {
        return context.habitLogDataStore.data.map { it[CURRENT_LOG_ID_KEY] }.first()
    }

    suspend fun getLogById(context: Context, logId: String): HabitReminderLog? {
        val prefs = context.habitLogDataStore.data.first()
        val current = prefs[LOGS_KEY]?.let { json.decodeFromString<List<HabitReminderLog>>(it) } ?: emptyList()
        return current.find { it.logId == logId }
    }

    fun createLog(habitId: String, title: String): HabitReminderLog {
        return HabitReminderLog(
            logId = UUID.randomUUID().toString(),
            habitId = habitId,
            title = title,
            triggeredAt = System.currentTimeMillis(),
            status = HabitReminderLog.Status.TRIGGERED
        )
    }
}
