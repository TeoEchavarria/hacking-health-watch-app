package com.example.sensorstreamerwearos.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Broadcast receiver for workout notification actions (DECLINE).
 */
class WorkoutNotificationActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "WorkoutNotificationAction"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "DECLINE") {
            val routineId = intent.getStringExtra("routineId")
            Log.d(TAG, "DECLINE action received for routine: $routineId")
            
            // Clear pending routine
            WorkoutMessageReceiverService().clearPendingRoutine(context)
            
            // Dismiss notification (already handled by autoCancel, but we can explicitly cancel)
            NotificationManagerCompat.from(context)
                .cancel(WorkoutMessageReceiverService.NOTIFICATION_ID)
        }
    }
}
