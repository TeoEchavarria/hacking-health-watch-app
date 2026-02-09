package com.example.sensorstreamerwearos.habit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Separate DataStore for habit retry state. Does NOT use workout keys.
 */
object HabitRetryStore {
    private val Context.habitRetryDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "habit_reminder_prefs"
    )

    private val HABIT_ID = stringPreferencesKey("retry_habit_id")
    private val TITLE = stringPreferencesKey("retry_title")
    private val SOURCE_NODE_ID = stringPreferencesKey("retry_source_node_id")
    private val RETRY_COUNT = stringPreferencesKey("retry_count")

    suspend fun saveRetryPayload(context: Context, habitId: String, title: String, sourceNodeId: String?, retryCount: Int) {
        context.habitRetryDataStore.edit { prefs ->
            prefs[HABIT_ID] = habitId
            prefs[TITLE] = title
            prefs[SOURCE_NODE_ID] = sourceNodeId ?: ""
            prefs[RETRY_COUNT] = retryCount.toString()
        }
    }

    suspend fun getRetryPayload(context: Context): RetryPayload? {
        val prefs = context.habitRetryDataStore.data.map { it }.first()
        val habitId = prefs[HABIT_ID] ?: return null
        val title = prefs[TITLE] ?: return null
        return RetryPayload(
            habitId = habitId,
            title = title,
            sourceNodeId = prefs[SOURCE_NODE_ID].takeIf { it?.isNotEmpty() == true },
            retryCount = prefs[RETRY_COUNT]?.toIntOrNull() ?: 0
        )
    }

    suspend fun clearRetryPayload(context: Context) {
        context.habitRetryDataStore.edit { it.clear() }
    }

    data class RetryPayload(
        val habitId: String,
        val title: String,
        val sourceNodeId: String?,
        val retryCount: Int
    )
}
