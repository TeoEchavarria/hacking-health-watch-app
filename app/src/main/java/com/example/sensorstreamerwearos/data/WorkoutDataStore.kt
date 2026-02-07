package com.example.sensorstreamerwearos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single DataStore instance for workout state. All workout-related components
 * (WorkoutService, WorkoutMessageReceiverService, etc.) MUST use this provider
 * only. Never create preferencesDataStore(name = "workout_prefs") elsewhere.
 */
object WorkoutDataStoreProvider {
    private val Context.workoutDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "workout_prefs"
    )

    private val ACTIVE_SESSION_ID_KEY = stringPreferencesKey("active_session_id")
    val PENDING_PAYLOAD_KEY = stringPreferencesKey("pending_payload")
    val PENDING_ROUTINE_KEY = stringPreferencesKey("pending_routine")

    fun getDataStore(context: Context): DataStore<Preferences> =
        context.workoutDataStore

    fun getActiveSessionIdFlow(context: Context): Flow<String?> =
        context.workoutDataStore.data.map { prefs ->
            prefs[ACTIVE_SESSION_ID_KEY]
        }

    suspend fun getActiveSessionId(context: Context): String? =
        context.workoutDataStore.data.map { prefs ->
            prefs[ACTIVE_SESSION_ID_KEY]
        }.first()

    suspend fun setActiveSessionId(context: Context, sessionId: String) {
        context.workoutDataStore.edit { prefs ->
            prefs[ACTIVE_SESSION_ID_KEY] = sessionId
        }
    }

    suspend fun clearActiveSessionId(context: Context) {
        context.workoutDataStore.edit { prefs ->
            prefs.remove(ACTIVE_SESSION_ID_KEY)
        }
    }
}
