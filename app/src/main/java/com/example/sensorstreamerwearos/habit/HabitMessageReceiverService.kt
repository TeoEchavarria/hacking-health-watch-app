package com.example.sensorstreamerwearos.habit

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Receives /habit/reminder/start from the phone and starts HabitService.
 */
class HabitMessageReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "HabitReminder"
        const val PATH_HABIT_REMINDER_START = "/habit/reminder/start"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != PATH_HABIT_REMINDER_START) return

        try {
            val payload = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            val habitId = payload.optString("habitId", "")
            val title = payload.optString("title", "Habit")
            val triggerAt = payload.optString("triggerAt", "")

            Log.i(TAG, "Reminder received habitId=$habitId title=$title")

            val serviceIntent = Intent(this, HabitService::class.java).apply {
                action = HabitService.ACTION_SHOW_REMINDER
                putExtra(HabitService.EXTRA_HABIT_ID, habitId)
                putExtra(HabitService.EXTRA_TITLE, title)
                putExtra(HabitService.EXTRA_TRIGGER_AT, triggerAt)
                putExtra(HabitService.EXTRA_SOURCE_NODE_ID, messageEvent.sourceNodeId)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle habit reminder", e)
        }
    }
}
