package com.example.sensorstreamerwearos.workout.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LifecycleService
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.example.sensorstreamerwearos.R
import com.example.sensorstreamerwearos.workout.WorkoutMessageReceiverService
import com.example.sensorstreamerwearos.workout.model.RoutineBlockPayload
import com.example.sensorstreamerwearos.workout.model.RoutinePayload
import com.example.sensorstreamerwearos.workout.model.WorkoutAckPayload
import com.example.sensorstreamerwearos.workout.model.WorkoutEventPayload
import com.example.sensorstreamerwearos.workout.ui.WorkoutTimerActivity
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Foreground service that manages workout WORK/REST state machine.
 * UI observes WorkoutUiState; user actions are sent via startService intents.
 */
class WorkoutService : LifecycleService() {

    companion object {
        private const val TAG = "WorkoutService"
        private const val WORKOUT_PROTOCOL_TAG = "WorkoutProtocol"
        private const val WORKOUT_UI_TAG = "WorkoutUI"
        private const val WORKOUT_HAPTICS_TAG = "WorkoutHaptics"
        private const val NOTIFICATION_CHANNEL_ID = "workout_service_channel"
        private const val NOTIFICATION_ID = 2001
        private const val WORKOUT_ACK_PATH = "/workout/ack"
        private const val WORKOUT_EVENT_PATH = "/workout/event"
        private const val TICK_INTERVAL_MS = 1000L

        const val ACTION_START_SESSION = "com.example.sensorstreamerwearos.workout.ACTION_START_SESSION"
        const val ACTION_DONE_SET = "com.example.sensorstreamerwearos.workout.ACTION_DONE_SET"
        const val ACTION_SKIP_REST = "com.example.sensorstreamerwearos.workout.ACTION_SKIP_REST"
        const val ACTION_PAUSE = "com.example.sensorstreamerwearos.workout.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.sensorstreamerwearos.workout.ACTION_RESUME"
        const val ACTION_STOP = "com.example.sensorstreamerwearos.workout.ACTION_STOP"
        const val ACTION_EXTEND_REST = "com.example.sensorstreamerwearos.workout.ACTION_EXTEND_REST"

        private val PENDING_PAYLOAD_KEY = stringPreferencesKey("pending_payload")
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "workout_prefs")

    inner class LocalBinder : android.os.Binder() {
        fun getService(): WorkoutService = this@WorkoutService
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }
    private val binder = LocalBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    private var payload: RoutinePayload? = null
        private set
    private var sourceNodeId: String? = null
    private var sessionId: String? = null

    enum class Mode { IDLE, WORK, REST }
    private var mode = Mode.IDLE
    private var blockIndex = 0
    private var setIndex = 0
    private var workElapsedSec = 0
    private var restRemainingSec = 0
    private var isPaused = false

    private val _uiState = MutableStateFlow(WorkoutUiState())
    val uiState: StateFlow<WorkoutUiState> = _uiState.asStateFlow()

    data class WorkoutUiState(
        val routineName: String = "",
        val exerciseName: String = "",
        val setIndex: Int = 0,
        val totalSets: Int = 0,
        val targetWeight: Float = 0f,
        val targetReps: Int? = null,
        val mode: Mode = Mode.IDLE,
        val workElapsedSec: Int = 0,
        val restRemainingSec: Int = 0,
        val isPaused: Boolean = false,
        val isFinished: Boolean = false,
        val progress: Float = 0f
    )

    fun isWorkoutActive(): Boolean = mode != Mode.IDLE && !_uiState.value.isFinished
    fun getActiveSessionId(): String? = sessionId

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "WorkoutService created")
        Log.i(WORKOUT_UI_TAG, "WorkoutService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_START_SESSION -> handleStartSession(intent)
            ACTION_DONE_SET -> handleDoneSet()
            ACTION_SKIP_REST -> handleSkipRest()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            ACTION_EXTEND_REST -> handleExtendRest()
            else -> Log.d(TAG, "Unknown action=${intent.action}")
        }
        return START_STICKY
    }

    private fun handleStartSession(intent: Intent) {
        val sid = intent.getStringExtra("sessionId")
        val payloadJson = intent.getStringExtra("payloadJson")
        sourceNodeId = intent.getStringExtra("sourceNodeId")

        Log.i(WORKOUT_PROTOCOL_TAG, "WorkoutService validating sessionId=$sid")

        val parsed: RoutinePayload? = if (!payloadJson.isNullOrBlank()) {
            try {
                json.decodeFromString<RoutinePayload>(payloadJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse payloadJson", e)
                null
            }
        } else null

        val finalPayload = parsed ?: run {
            val stored = loadPendingPayload()
            if (stored != null && stored.sessionId == sid) stored else null
        }

        if (finalPayload == null || finalPayload.blocks.isEmpty()) {
            val reason = when {
                sid == null -> "missing_sessionId"
                else -> "invalid_payload_or_empty_blocks"
            }
            Log.e(WORKOUT_PROTOCOL_TAG, "WorkoutService REJECTED sessionId=$sid reason=$reason")
            sourceNodeId?.let { nid ->
                serviceScope.launch { sendAck(nid, sid ?: "", finalPayload?.routineId ?: "", "REJECTED", reason) }
            }
            stopSelf()
            return
        }

        payload = finalPayload
        sessionId = sid
        blockIndex = 0
        setIndex = 0
        mode = Mode.WORK
        workElapsedSec = 0
        restRemainingSec = 0
        isPaused = false

        if (_uiState.value.mode == Mode.IDLE) {
            startForeground(NOTIFICATION_ID, createNotificationBuilder("Starting...").build())
        }

        updateUiFromState()
        startTickJob()
        updateNotification()
        vibrateStart()

        Log.i(WORKOUT_UI_TAG, "WorkoutService started")
        Log.i(WORKOUT_PROTOCOL_TAG, "WorkoutService STARTED sessionId=${finalPayload.sessionId} blocks=${finalPayload.blocks.size}")

        sourceNodeId?.let { nid ->
            serviceScope.launch {
                sendAck(nid, finalPayload.sessionId, finalPayload.routineId, "STARTED", null)
                launchWorkoutTimerActivity()
            }
        }
    }

    private fun launchWorkoutTimerActivity() {
        try {
            val intent = Intent(this, WorkoutTimerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                sessionId?.let { putExtra("sessionId", it) }
            }
            startActivity(intent)
            Log.i(WORKOUT_UI_TAG, "WorkoutTimerActivity launched")
        } catch (e: Exception) {
            Log.e(WORKOUT_UI_TAG, "Failed to launch WorkoutTimerActivity", e)
        }
    }

    private fun handleDoneSet() {
        if (mode != Mode.WORK || isPaused) return
        val blk = currentBlock() ?: return

        sourceNodeId?.let { nid ->
            serviceScope.launch { sendEvent(nid, "DONE_SET", blk.blockId, setIndex) }
        }
        vibrateDone()

        val restSec = blk.restSec
        if (restSec <= 0) {
            advanceSetOrBlock()
        } else {
            mode = Mode.REST
            restRemainingSec = restSec
            updateUiFromState()
        }
    }

    private fun handleSkipRest() {
        if (mode != Mode.REST) return
        advanceSetOrBlock()
    }

    private fun handleExtendRest() {
        if (mode != Mode.REST) return
        restRemainingSec += 10
        updateUiFromState()
    }

    private fun handlePause() {
        if (mode == Mode.IDLE || mode == Mode.REST) return
        isPaused = true
        timerJob?.cancel()
        updateUiFromState()
        updateNotification()
    }

    private fun handleResume() {
        if (!isPaused) return
        isPaused = false
        startTickJob()
        updateUiFromState()
        updateNotification()
    }

    private fun handleStop() {
        sourceNodeId?.let { nid ->
            serviceScope.launch { sendEvent(nid, "STOP", currentBlock()?.blockId ?: "", setIndex) }
        }
        finishWorkout()
    }

    private fun advanceSetOrBlock() {
        val blk = currentBlock() ?: return
        setIndex++
        if (setIndex >= blk.sets) {
            setIndex = 0
            blockIndex++
            if (blockIndex >= (payload?.blocks?.size ?: 0)) {
                sourceNodeId?.let { nid ->
                    serviceScope.launch { sendEvent(nid, "FINISH", blk.blockId, setIndex - 1) }
                }
                finishWorkout()
                return
            }
        }
        mode = Mode.WORK
        workElapsedSec = 0
        restRemainingSec = 0
        updateUiFromState()
        updateNotification()
    }

    private fun finishWorkout() {
        timerJob?.cancel()
        mode = Mode.IDLE
        _uiState.value = _uiState.value.copy(isFinished = true, mode = Mode.IDLE)
        updateNotification()
        stopForeground(true)
        stopSelf()
    }

    private fun currentBlock(): RoutineBlockPayload? {
        val blocks = payload?.blocks ?: return null
        if (blockIndex >= blocks.size) return null
        return blocks[blockIndex]
    }

    private fun updateUiFromState() {
        val blk = currentBlock()
        val progress = when (mode) {
            Mode.REST -> {
                if (blk != null && blk.restSec > 0) {
                    restRemainingSec.toFloat() / blk.restSec.toFloat()
                } else 0f
            }
            Mode.WORK -> {
                // Visual progress: fills up over 60 seconds
                (workElapsedSec % 60) / 60f
            }
            else -> 0f
        }

        _uiState.value = WorkoutUiState(
            routineName = payload?.routineName ?: "",
            exerciseName = blk?.exerciseName ?: "",
            setIndex = setIndex + 1,
            totalSets = blk?.sets ?: 0,
            targetWeight = blk?.targetWeight ?: 0f,
            targetReps = blk?.targetReps,
            mode = mode,
            workElapsedSec = workElapsedSec,
            restRemainingSec = restRemainingSec,
            isPaused = isPaused,
            isFinished = false,
            progress = progress
        )
    }

    private fun startTickJob() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (!isPaused) {
                delay(TICK_INTERVAL_MS)
                if (mode == Mode.WORK) {
                    workElapsedSec++
                    updateUiFromState()
                } else if (mode == Mode.REST) {
                    restRemainingSec--
                    if (restRemainingSec <= 0) {
                        restRemainingSec = 0
                        vibrateRestFinished()
                        handleSkipRest()
                        continue
                    }
                    updateUiFromState()
                } else break
                updateNotification()
                val sec = if (mode == Mode.WORK) workElapsedSec else restRemainingSec
                Log.d(TAG, "timer tick mode=$mode work=$workElapsedSec rest=$restRemainingSec")
                Log.i(WORKOUT_UI_TAG, "Timer tick sec=$sec")
            }
        }
    }

    private fun updateNotification() {
        val s = _uiState.value
        val text = when {
            s.isFinished -> "FINISHED"
            s.mode == Mode.WORK -> "${s.exerciseName} · Set ${s.setIndex}/${s.totalSets} · ${formatTime(s.workElapsedSec)}"
            s.mode == Mode.REST -> "Rest · ${formatTime(s.restRemainingSec)}"
            else -> "Workout"
        }
        val notification = createNotificationBuilder(text).build()
        ongoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, createNotificationBuilder(text))
            .setAnimatedIcon(R.drawable.ic_workout)
            .setStaticIcon(R.drawable.ic_workout)
            .setTouchIntent(createTouchIntent())
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
        ongoingActivity?.apply(this)
        startForeground(NOTIFICATION_ID, notification)
    }

    private var ongoingActivity: OngoingActivity? = null

    private fun createNotificationBuilder(statusText: String): NotificationCompat.Builder {
        val intent = Intent(this, WorkoutTimerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            sessionId?.let { putExtra("sessionId", it) }
        }
        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Workout")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Workout",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Workout session status"
        }
        manager.createNotificationChannel(channel)
    }

    private fun createTouchIntent(): PendingIntent {
        val intent = Intent(this, WorkoutTimerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            sessionId?.let { putExtra("sessionId", it) }
        }
        return TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(1, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrateStart() {
        val vibrator = getVibrator() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(WORKOUT_HAPTICS_TAG, "Vibrate: workout started")
        }
    }

    private fun vibrateDone() {
        val vibrator = getVibrator() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), intArrayOf(0, 255, 0, 255), -1))
            Log.i(WORKOUT_HAPTICS_TAG, "Vibrate: set done")
        }
    }

    private fun vibrateRestFinished() {
        val vibrator = getVibrator() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            Log.i(WORKOUT_HAPTICS_TAG, "Vibrate: rest finished")
        }
    }

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
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
                Log.i(WORKOUT_PROTOCOL_TAG, "TX /workout/ack status=$status")
            } catch (e: Exception) {
                Log.e(WORKOUT_PROTOCOL_TAG, "Failed to send ACK", e)
            }
        }
    }

    private suspend fun sendEvent(nodeId: String, type: String, blockId: String, setIdx: Int) {
        withContext(Dispatchers.IO) {
            try {
                val payload = WorkoutEventPayload(
                    sessionId = sessionId ?: "",
                    type = type,
                    blockId = blockId,
                    setIndex = setIdx,
                    at = java.time.Instant.now().toString()
                )
                val bytes = json.encodeToString(WorkoutEventPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
                Wearable.getMessageClient(applicationContext).sendMessage(nodeId, WORKOUT_EVENT_PATH, bytes).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send event", e)
            }
        }
    }

    private fun loadPendingPayload(): RoutinePayload? = runBlocking {
        try {
            val jsonStr = dataStore.data.first()[PENDING_PAYLOAD_KEY]
            jsonStr?.let { json.decodeFromString<RoutinePayload>(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending payload", e)
            null
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "WorkoutService destroyed")
        super.onDestroy()
    }
}
