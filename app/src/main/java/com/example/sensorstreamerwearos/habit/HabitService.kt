package com.example.sensorstreamerwearos.habit

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.sensorstreamerwearos.R
import com.example.sensorstreamerwearos.data.WorkoutDataStoreProvider
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
import org.json.JSONObject
import java.time.Instant

/**
 * Foreground service managing habit reminder lifecycle.
 * If workout is active, shows notification instead of full-screen.
 * Handles auto-retry loop (10 min) and configurable max retries.
 */
class HabitService : LifecycleService() {

    companion object {
        private const val TAG = "HabitReminder"
        private const val NOTIFICATION_CHANNEL_ID = "habit_reminder_channel"
        private const val NOTIFICATION_ID = 3001
        private const val TIMER_WINDOW_SEC = 60
        private const val POSTPONE_DELAY_MS = 10 * 60 * 1000L
        private const val MAX_RETRIES = 5

        const val ACTION_SHOW_REMINDER = "com.example.sensorstreamerwearos.habit.ACTION_SHOW_REMINDER"
        const val ACTION_DONE = "com.example.sensorstreamerwearos.habit.ACTION_DONE"
        const val ACTION_POSTPONE = "com.example.sensorstreamerwearos.habit.ACTION_POSTPONE"
        const val ACTION_TIMER_FINISHED = "com.example.sensorstreamerwearos.habit.ACTION_TIMER_FINISHED"

        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TRIGGER_AT = "triggerAt"
        const val EXTRA_SOURCE_NODE_ID = "sourceNodeId"
        const val EXTRA_RETRY_COUNT = "retryCount"
        private const val RETRY_ACTION = "com.example.sensorstreamerwearos.habit.RETRY_REMINDER"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    private var currentLogId: String? = null

    private var habitId: String = ""
    private var title: String = ""
    private var sourceNodeId: String? = null
    private var retryCount: Int = 0

    private val _uiState = MutableStateFlow(HabitUiState())
    val uiState: StateFlow<HabitUiState> = _uiState.asStateFlow()

    data class HabitUiState(
        val title: String = "",
        val progress: Float = 1f,
        val isActive: Boolean = false
    )

    inner class LocalBinder : android.os.Binder() {
        fun getService(): HabitService = this@HabitService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent) = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "HabitService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_REMINDER -> handleShowReminder(intent)
            ACTION_DONE -> handleDone(intent)
            ACTION_POSTPONE -> handlePostpone(intent)
            ACTION_TIMER_FINISHED -> handleTimerFinished()
        }
        return START_STICKY
    }

    private fun handleShowReminder(intent: Intent) {
        habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Habit"
        sourceNodeId = intent.getStringExtra(EXTRA_SOURCE_NODE_ID)
        retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)

        Log.i(TAG, "Reminder timer started habitId=$habitId title=$title retryCount=$retryCount")

        val workoutActive = runBlocking { WorkoutDataStoreProvider.getActiveSessionId(applicationContext) != null }
        if (workoutActive) {
            Log.i(TAG, "Workout active - showing notification instead of full-screen")
            showNotificationOnly()
            stopSelf()
            return
        }

        retryCount = 0
        startForeground(NOTIFICATION_ID, createNotificationBuilder(title).build())
        vibrateReminderAppear()
        val log = HabitReminderLogStore.createLog(habitId, title)
        currentLogId = log.logId
        serviceScope.launch {
            try {
                HabitReminderLogStore.appendLog(applicationContext, log)
                Log.i(TAG, "Log persisted")
                sendLogToPhone(log)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist or send log", e)
            }
        }
        Log.i(TAG, "Reminder triggered habitId=$habitId")
        _uiState.value = HabitUiState(title = title, progress = 1f, isActive = true)
        launchHabitReminderActivity()
        startTimerJob()
    }

    private fun launchHabitReminderActivity() {
        try {
            val intent = Intent(this, HabitReminderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SOURCE_NODE_ID, sourceNodeId)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch HabitReminderActivity", e)
        }
    }

    private fun startTimerJob() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var remaining = TIMER_WINDOW_SEC
            while (remaining > 0 && _uiState.value.isActive) {
                delay(1000)
                remaining--
                val progress = remaining.toFloat() / TIMER_WINDOW_SEC
                _uiState.value = _uiState.value.copy(progress = progress)
            }
            if (_uiState.value.isActive) {
                handleTimerFinished()
            }
        }
    }

    private fun handleTimerFinished() {
        timerJob?.cancel()
        stopVibration()
        updateLogAndSyncToPhone(HabitReminderLog.Status.POSTPONED)
        _uiState.value = _uiState.value.copy(isActive = false)
        if (retryCount >= MAX_RETRIES) {
            Log.i(TAG, "Max retries ($MAX_RETRIES) reached, stopping")
            stopForeground(true)
            stopSelf()
            return
        }
        val nextRetry = retryCount + 1
        Log.i(TAG, "Reminder timer finished, scheduling retry $nextRetry in 10 min")
        scheduleRetry(nextRetry)
        stopForeground(true)
        stopSelf()
    }

    private fun scheduleRetry(nextRetryCount: Int) {
        serviceScope.launch {
            HabitRetryStore.saveRetryPayload(applicationContext, habitId, title, sourceNodeId, nextRetryCount)
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, HabitAlarmReceiver::class.java).apply {
            action = RETRY_ACTION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            habitId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + POSTPONE_DELAY_MS
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun handleDone(intent: Intent) {
        habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: habitId
        sourceNodeId = intent.getStringExtra(EXTRA_SOURCE_NODE_ID) ?: sourceNodeId
        timerJob?.cancel()
        stopVibration()
        updateLogAndSyncToPhone(HabitReminderLog.Status.DONE)
        _uiState.value = _uiState.value.copy(isActive = false)
        vibrateDone()
        sendDoneToPhone()
        stopForeground(true)
        stopSelf()
    }

    private fun handlePostpone(intent: Intent) {
        habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: habitId
        sourceNodeId = intent.getStringExtra(EXTRA_SOURCE_NODE_ID) ?: sourceNodeId
        timerJob?.cancel()
        stopVibration()
        updateLogAndSyncToPhone(HabitReminderLog.Status.POSTPONED)
        _uiState.value = _uiState.value.copy(isActive = false)
        vibratePostpone()
        val postponedUntil = Instant.now().plusMillis(POSTPONE_DELAY_MS).toString()
        sendPostponeToPhone(postponedUntil)
        scheduleRetry(retryCount)
        stopForeground(true)
        stopSelf()
    }

    private fun sendDoneToPhone() {
        sourceNodeId?.let { nodeId ->
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val payload = JSONObject().apply {
                            put("habitId", habitId)
                        }
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(nodeId, "/habit/reminder/done", payload.toString().toByteArray())
                            .await()
                        Log.i(TAG, "Reminder completed, sent to phone")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send done to phone", e)
                    }
                }
            }
        }
    }

    private fun updateLogAndSyncToPhone(status: HabitReminderLog.Status) {
        val logId = currentLogId ?: return
        serviceScope.launch {
            try {
                val updated = HabitReminderLogStore.updateLogStatus(applicationContext, logId, status)
                updated?.let { sendLogToPhone(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update log", e)
            }
        }
    }

    private fun sendLogToPhone(log: HabitReminderLog) {
        sourceNodeId?.let { nodeId ->
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val payload = JSONObject().apply {
                            put("habitId", log.habitId)
                            put("title", log.title)
                            put("triggeredAt", log.triggeredAt)
                            put("status", log.status.name)
                        }
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(nodeId, "/habit/log", payload.toString().toByteArray())
                            .await()
                        Log.i(TAG, "Log sent to phone")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send log to phone", e)
                    }
                }
            }
        }
    }

    private fun sendPostponeToPhone(postponedUntil: String) {
        sourceNodeId?.let { nodeId ->
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val payload = JSONObject().apply {
                            put("habitId", habitId)
                            put("postponedUntil", postponedUntil)
                        }
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(nodeId, "/habit/reminder/postpone", payload.toString().toByteArray())
                            .await()
                        Log.i(TAG, "Reminder postponed, sent to phone")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send postpone to phone", e)
                    }
                }
            }
        }
    }

    private fun showNotificationOnly() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Habit Reminder")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationBuilder(statusText: String): NotificationCompat.Builder {
        val intent = Intent(this, HabitReminderActivity::class.java).apply {
            putExtra(EXTRA_HABIT_ID, habitId)
            putExtra(EXTRA_TITLE, title)
        }
        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Habit Reminder")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Habit Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
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

    private fun vibrateReminderAppear() {
        getVibrator()?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasVibrator()) {
                // Single vibration pattern: 0ms delay, 500ms on, 200ms off, 500ms on
                // -1 means no repeat (single vibration)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
                Log.i(TAG, "Vibrate triggered (single) for habitId=$habitId")
            }
        }
    }

    private fun stopVibration() {
        getVibrator()?.cancel()
    }

    private fun vibrateDone() {
        getVibrator()?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), intArrayOf(0, 255, 0, 255), -1))
            }
        }
    }

    private fun vibratePostpone() {
        getVibrator()?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE - 50))
            }
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        stopVibration()
        serviceScope.cancel()
        super.onDestroy()
    }
}
