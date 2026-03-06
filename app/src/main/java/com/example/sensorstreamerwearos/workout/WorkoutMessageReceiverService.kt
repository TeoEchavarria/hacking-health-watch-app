package com.example.sensorstreamerwearos.workout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.example.sensorstreamerwearos.data.WorkoutDataStoreProvider
import com.example.sensorstreamerwearos.BuildConfig
import com.example.sensorstreamerwearos.R
import com.example.sensorstreamerwearos.workout.model.Routine
import com.example.sensorstreamerwearos.workout.model.RoutinePayload
import com.example.sensorstreamerwearos.workout.model.WorkoutAckMessage
import com.example.sensorstreamerwearos.workout.model.WorkoutAckPayload
import com.example.sensorstreamerwearos.workout.model.WorkoutEventPayload
import com.example.sensorstreamerwearos.workout.model.WorkoutProtocol
import com.example.sensorstreamerwearos.workout.model.WorkoutStartMessage
import com.example.sensorstreamerwearos.workout.model.WorkoutStatePayload
import com.example.sensorstreamerwearos.workout.model.WorkoutStateRequestPayload
import com.example.sensorstreamerwearos.workout.repository.RoutineRepository
import com.example.sensorstreamerwearos.workout.service.WorkoutService
import com.example.sensorstreamerwearos.workout.ui.WorkoutTimerActivity
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Service that receives workout routine messages from the phone app.
 * Handles /workout/start with RoutinePayload (blocks/sets model) and /workout/push (legacy).
 */
class WorkoutMessageReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "WorkoutMessageReceiver"
        private const val WORKOUT_PROTOCOL_TAG = "WorkoutProtocol"
        private const val WORKOUT_WATCH_TAG = "WorkoutWatch"
        const val MESSAGE_PATH_PUSH = "/workout/push"
        const val MESSAGE_PATH_START = "/workout/start"
        const val MESSAGE_PATH_STATE_REQUEST = "/workout/state_request"
        const val MESSAGE_PATH_EVENT = "/workout/event"
        private const val NOTIFICATION_CHANNEL_ID = "workout_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SESSION = "com.example.sensorstreamerwearos.workout.ACTION_START_SESSION"
        const val ACTION_START_WORKOUT = "com.example.sensorstreamerwearos.workout.ACTION_START_WORKOUT"

        private const val WORKOUT_ACK_PATH = "/workout/ack"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "WorkoutMessageReceiverService created")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        Log.i(TAG, "onMessageReceived path=${messageEvent.path} bytes=${messageEvent.data.size}")
        when (messageEvent.path) {
            MESSAGE_PATH_START -> handleWorkoutStart(messageEvent)
            MESSAGE_PATH_PUSH -> handleWorkoutPush(messageEvent)
            MESSAGE_PATH_STATE_REQUEST -> handleStateRequest(messageEvent)
            MESSAGE_PATH_EVENT -> handleWorkoutEvent(messageEvent)
            else -> { }
        }
    }

    private fun handleWorkoutEvent(messageEvent: MessageEvent) {
        val activeSessionId = runBlocking { WorkoutDataStoreProvider.getActiveSessionId(applicationContext) }
        
        try {
            val jsonPayload = String(messageEvent.data, Charsets.UTF_8)
            Log.i(TAG, "handleWorkoutEvent: activeSessionId=$activeSessionId payload=$jsonPayload")
            
            // Parse payload to check event type
            val eventPayload = try {
                json.decodeFromString<WorkoutEventPayload>(jsonPayload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse workout event", e)
                return
            }
            
            // Always process FINISH_WORKOUT to ensure watch closes even if session state is stale
            if (activeSessionId == null && eventPayload.type != "FINISH_WORKOUT") {
                Log.w(TAG, "No active session, ignoring ${eventPayload.type}")
                return
            }
            
            val serviceIntent = Intent(this, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_REMOTE_EVENT
                putExtra("payloadJson", jsonPayload)
                putExtra("sourceNodeId", messageEvent.sourceNodeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle workout event", e)
        }
    }

    /**
     * Handle /workout/start: Protocol v2 first (WorkoutStartMessage), then fallback to v1 (RoutinePayload).
     * Always send ACK (V2 format when possible). Never throw without sending an ACK.
     */
    private fun handleWorkoutStart(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val rawJson = String(messageEvent.data, Charsets.UTF_8)
        Log.i(TAG, "onMessageReceived path=${messageEvent.path} bytes=${messageEvent.data.size}")
        if (BuildConfig.DEBUG) {
            Log.d(WORKOUT_PROTOCOL_TAG, "raw JSON (debug): $rawJson")
        }

        // Try V2 decode first
        val v2Start = try {
            json.decodeFromString<WorkoutStartMessage>(rawJson)
        } catch (e: Exception) {
            Log.e(WORKOUT_PROTOCOL_TAG, "Failed to decode WorkoutStartMessage", e)
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId = "",
                    attemptId = "",
                    success = false,
                    reasonCode = WorkoutProtocol.ReasonCode.INVALID_JSON,
                    reasonMessage = e.message
                )
            }
            return
        }

        val attemptId = v2Start.attemptId
        val sessionId = v2Start.sessionId
        val routineId = v2Start.routineId
        val routineName = v2Start.routineName
        val blockId = v2Start.blockId
        Log.i(WORKOUT_PROTOCOL_TAG, "event=workout_start_rx attemptId=$attemptId sessionId=$sessionId routineId=$routineId blockId=$blockId")

        // Validate required fields
        if (sessionId.isBlank() || attemptId.isBlank() || routineId.isBlank() || routineName.isBlank()) {
            Log.w(WORKOUT_PROTOCOL_TAG, "event=invalid_schema attemptId=$attemptId reason=missing_required_fields")
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId,
                    attemptId,
                    success = false,
                    reasonCode = WorkoutProtocol.ReasonCode.INVALID_SCHEMA,
                    reasonMessage = "sessionId, attemptId, routineId, routineName required"
                )
            }
            return
        }

        // Session already active?
        val activeSessionId = runBlocking { WorkoutDataStoreProvider.getActiveSessionId(applicationContext) }
        if (activeSessionId != null && activeSessionId != sessionId) {
            Log.i(WORKOUT_PROTOCOL_TAG, "event=reject_session_active attemptId=$attemptId active=$activeSessionId")
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId,
                    attemptId,
                    success = false,
                    reasonCode = "SESSION_ALREADY_ACTIVE",
                    reasonMessage = activeSessionId
                )
            }
            return
        }

        // Load routine by routineId
        val sentAtStr = java.time.Instant.ofEpochMilli(v2Start.sentAt).toString()
        val payload = try {
            RoutineRepository.getRoutine(routineId, sessionId, routineName, sentAtStr)
        } catch (e: Exception) {
            Log.e(WORKOUT_PROTOCOL_TAG, "event=internal_error attemptId=$attemptId", e)
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId,
                    attemptId,
                    success = false,
                    reasonCode = WorkoutProtocol.ReasonCode.INTERNAL_ERROR,
                    reasonMessage = e.message
                )
            }
            return
        }

        if (payload == null) {
            Log.w(WORKOUT_PROTOCOL_TAG, "event=routine_not_found attemptId=$attemptId routineId=$routineId")
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId,
                    attemptId,
                    success = false,
                    reasonCode = WorkoutProtocol.ReasonCode.ROUTINE_NOT_FOUND,
                    reasonMessage = routineId
                )
            }
            return
        }

        // Optional: validate blockId if provided
        if (!blockId.isNullOrBlank() && !RoutineRepository.hasBlock(routineId, blockId)) {
            Log.w(WORKOUT_PROTOCOL_TAG, "event=block_not_found attemptId=$attemptId blockId=$blockId")
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId,
                    attemptId,
                    success = false,
                    reasonCode = WorkoutProtocol.ReasonCode.BLOCK_NOT_FOUND,
                    reasonMessage = blockId
                )
            }
            return
        }

        try {
            val payloadJson = json.encodeToString(RoutinePayload.serializer(), payload)
            
            serviceScope.launch {
                WorkoutDataStoreProvider.getDataStore(applicationContext).edit { prefs ->
                    prefs[WorkoutDataStoreProvider.PENDING_PAYLOAD_KEY] = payloadJson
                }
            }

            val serviceIntent = Intent(this@WorkoutMessageReceiverService, WorkoutService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra("sessionId", payload.sessionId)
                putExtra("payloadJson", payloadJson)
                putExtra("sourceNodeId", sourceNodeId)
            }
            Log.i(WORKOUT_PROTOCOL_TAG, "event=starting_workout_service attemptId=$attemptId blocks=${payload.blocks.size}")
            ContextCompat.startForegroundService(this@WorkoutMessageReceiverService, serviceIntent)

            // Attempt to bring UI to foreground
            val activityIntent = Intent(this@WorkoutMessageReceiverService, WorkoutTimerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            try {
                startActivity(activityIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start activity from background", e)
            }

            serviceScope.launch {
                sendAckV2(sourceNodeId, sessionId, attemptId, success = true, reasonCode = null, reasonMessage = null)
            }
        } catch (e: Exception) {
            Log.e(WORKOUT_PROTOCOL_TAG, "event=internal_error attemptId=$attemptId", e)
            serviceScope.launch {
                sendAckV2(
                    sourceNodeId,
                    sessionId,
                    attemptId,
                    success = false,
                    reasonCode = WorkoutProtocol.ReasonCode.INTERNAL_ERROR,
                    reasonMessage = e.message
                )
            }
        }
    }

    private suspend fun sendAckV2(
        nodeId: String,
        sessionId: String,
        attemptId: String,
        success: Boolean,
        reasonCode: String?,
        reasonMessage: String?
    ) {
        withContext(Dispatchers.IO) {
            try {
                val msg = WorkoutAckMessage(
                    protocolVersion = WorkoutProtocol.PROTOCOL_VERSION,
                    type = WorkoutProtocol.TYPE_WORKOUT_ACK,
                    sessionId = sessionId,
                    attemptId = attemptId,
                    success = success,
                    reasonCode = reasonCode,
                    reasonMessage = reasonMessage,
                    sentAt = System.currentTimeMillis()
                )
                val bytes = json.encodeToString(WorkoutAckMessage.serializer(), msg).toByteArray(Charsets.UTF_8)
                Wearable.getMessageClient(applicationContext).sendMessage(nodeId, WORKOUT_ACK_PATH, bytes).await()
                Log.i(WORKOUT_PROTOCOL_TAG, "event=ack_sent attemptId=$attemptId success=$success reasonCode=$reasonCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ACK V2", e)
            }
        }
    }

    private suspend fun sendAck(nodeId: String, sessionId: String, routineId: String, status: String, reason: String?) {
        withContext(Dispatchers.IO) {
            try {
                val payload = WorkoutAckPayload(
                    sessionId = sessionId,
                    routineId = routineId,
                    status = status,
                    reason = reason,
                    at = java.time.Instant.now().toString()
                )
                val bytes = json.encodeToString(WorkoutAckPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
                Wearable.getMessageClient(applicationContext).sendMessage(nodeId, WORKOUT_ACK_PATH, bytes).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ACK", e)
            }
        }
    }

    private fun showFallbackNotification(payload: RoutinePayload) {
        Log.i(WORKOUT_PROTOCOL_TAG, "Notification fallback posted")
        val notificationManager = NotificationManagerCompat.from(this)

        val activityIntent = Intent(this, WorkoutTimerActivity::class.java).apply {
            putExtra("sessionId", payload.sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(activityIntent)
            getPendingIntent(
                0,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle("Workout started")
            .setContentText("Tap to view timer")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Handle /workout/state_request: forward to WorkoutService if running,
     * otherwise send IDLE/FINISHED state to phone.
     */
    private fun handleStateRequest(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val sessionId = try {
            val jsonStr = String(messageEvent.data, Charsets.UTF_8)
            json.decodeFromString<WorkoutStateRequestPayload>(jsonStr).sessionId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse state_request payload", e)
            ""
        }
        val activeSessionId = runBlocking { WorkoutDataStoreProvider.getActiveSessionId(applicationContext) }
        if (activeSessionId != null) {
            val serviceIntent = Intent(this, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_STATE_REQUEST
                putExtra("sourceNodeId", sourceNodeId)
                putExtra("sessionId", sessionId)
            }
            startService(serviceIntent)
        } else {
            serviceScope.launch {
                sendIdleState(sourceNodeId, sessionId)
            }
        }
    }

    private suspend fun sendIdleState(nodeId: String, requestSessionId: String) {
        withContext(Dispatchers.IO) {
            try {
                val payload = WorkoutStatePayload(
                    sessionId = requestSessionId.ifBlank { "none" },
                    routineId = "",
                    exerciseName = "",
                    currentSet = 0,
                    totalSets = 0,
                    mode = "FINISHED",
                    progress = 0f,
                    updatedAt = java.time.Instant.now().toString()
                )
                val bytes = json.encodeToString(WorkoutStatePayload.serializer(), payload).toByteArray(Charsets.UTF_8)
                Wearable.getMessageClient(applicationContext).sendMessage(nodeId, "/workout/state", bytes).await()
                Log.i(WORKOUT_PROTOCOL_TAG, "State request: service not running, sent FINISHED")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send idle state", e)
            }
        }
    }

    private fun handleWorkoutPush(messageEvent: MessageEvent) {
        try {
            val jsonPayload = String(messageEvent.data, Charsets.UTF_8)
            val routine = Gson().fromJson(jsonPayload, Routine::class.java)
            serviceScope.launch {
                WorkoutDataStoreProvider.getDataStore(applicationContext).edit { prefs ->
                    prefs[WorkoutDataStoreProvider.PENDING_ROUTINE_KEY] = jsonPayload
                }
            }
            showWorkoutNotification(routine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse workout routine", e)
        }
    }

    private fun showWorkoutNotification(routine: Routine) {
        val notificationManager = NotificationManagerCompat.from(this)
        val acceptIntent = Intent(this, WorkoutTimerActivity::class.java).apply {
            putExtra("routineId", routine.routineId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val acceptPendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(acceptIntent)
            getPendingIntent(
                0,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        }
        val declineIntent = Intent(this, WorkoutNotificationActionReceiver::class.java).apply {
            action = "DECLINE"
            putExtra("routineId", routine.routineId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, 1, declineIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle("Workout ready")
            .setContentText("Tap to start your workout routine")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_workout, "ACCEPT", acceptPendingIntent)
            .addAction(R.drawable.ic_workout, "DECLINE", declinePendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Workout Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for workout routines" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun getPendingRoutine(context: Context): Routine? = runBlocking {
        try {
            val prefs = WorkoutDataStoreProvider.getDataStore(context).data.first()
            val jsonStr = prefs[WorkoutDataStoreProvider.PENDING_ROUTINE_KEY]
            jsonStr?.let { Gson().fromJson(it, Routine::class.java) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending routine", e)
            null
        }
    }

    fun clearPendingRoutine(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            WorkoutDataStoreProvider.getDataStore(context).edit { it.remove(WorkoutDataStoreProvider.PENDING_ROUTINE_KEY) }
        }
    }
}
