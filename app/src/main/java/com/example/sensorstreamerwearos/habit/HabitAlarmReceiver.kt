package com.example.sensorstreamerwearos.habit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking

/**
 * Receives alarm when habit reminder should retry after 10 minutes.
 */
class HabitAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HabitReminder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.example.sensorstreamerwearos.habit.RETRY_REMINDER") return

        val payload = runBlocking { HabitRetryStore.getRetryPayload(context) } ?: run {
            Log.w(TAG, "No retry payload found")
            return
        }
        Log.i(TAG, "Reminder reappears after 10 min (retry ${payload.retryCount})")

        val serviceIntent = Intent(context, HabitService::class.java).apply {
            action = HabitService.ACTION_SHOW_REMINDER
            putExtra(HabitService.EXTRA_HABIT_ID, payload.habitId)
            putExtra(HabitService.EXTRA_TITLE, payload.title)
            putExtra(HabitService.EXTRA_TRIGGER_AT, java.time.Instant.now().toString())
            putExtra(HabitService.EXTRA_SOURCE_NODE_ID, payload.sourceNodeId)
            putExtra(HabitService.EXTRA_RETRY_COUNT, payload.retryCount)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
