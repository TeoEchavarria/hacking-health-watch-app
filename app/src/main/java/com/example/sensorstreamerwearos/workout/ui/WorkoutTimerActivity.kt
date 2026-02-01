package com.example.sensorstreamerwearos.workout.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import com.example.sensorstreamerwearos.databinding.ActivityWorkoutTimerBinding
import com.example.sensorstreamerwearos.workout.service.WorkoutService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Minimalist workout timer. Exercise name, timer, one primary button.
 * Swipe right = DONE/SKIP, swipe left = PAUSE/EXTEND REST.
 */
class WorkoutTimerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WorkoutUI"
        private const val GESTURE_TAG = "WorkoutGesture"
        private const val SWIPE_THRESHOLD_DP = 60
        private const val SWIPE_VELOCITY_THRESHOLD = 100
    }

    private lateinit var binding: ActivityWorkoutTimerBinding
    private var workoutService: WorkoutService? = null
    private var isBound = false

    private var swipeThresholdPx = 0

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val absDx = kotlin.math.abs(dx)
            val absDy = kotlin.math.abs(e2.y - e1.y)
            if (absDy > absDx) return false
            if (absDx < swipeThresholdPx) return false

            return when {
                dx > 0 && velocityX > SWIPE_VELOCITY_THRESHOLD -> {
                    Log.i(GESTURE_TAG, "Swipe RIGHT → DONE")
                    performDoneAction()
                    true
                }
                dx < 0 && velocityX < -SWIPE_VELOCITY_THRESHOLD -> {
                    Log.i(GESTURE_TAG, "Swipe LEFT → WAIT")
                    performWaitAction()
                    true
                }
                else -> false
            }
        }
    }

    private lateinit var gestureDetector: GestureDetectorCompat

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WorkoutService.LocalBinder
            workoutService = binder.getService()
            isBound = true
            Log.i(TAG, "WorkoutTimerActivity visible")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            workoutService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        swipeThresholdPx = (SWIPE_THRESHOLD_DP * resources.displayMetrics.density).toInt()
        gestureDetector = GestureDetectorCompat(this, gestureListener)

        val sessionId = intent.getStringExtra("sessionId")
        Log.i(TAG, "WorkoutTimerActivity onCreate sessionId=$sessionId")

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        binding.doneButton.setOnClickListener { performDoneAction() }

        val serviceIntent = Intent(this, WorkoutService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun performDoneAction() {
        val service = workoutService ?: return
        val state = service.uiState.value
        when (state.mode) {
            WorkoutService.Mode.WORK -> sendAction(WorkoutService.ACTION_DONE_SET)
            WorkoutService.Mode.REST -> sendAction(WorkoutService.ACTION_SKIP_REST)
            WorkoutService.Mode.IDLE -> { }
        }
    }

    private fun performWaitAction() {
        val service = workoutService ?: return
        val state = service.uiState.value
        when (state.mode) {
            WorkoutService.Mode.WORK -> sendAction(WorkoutService.ACTION_PAUSE)
            WorkoutService.Mode.REST -> sendAction(WorkoutService.ACTION_EXTEND_REST)
            WorkoutService.Mode.IDLE -> { }
        }
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, WorkoutService::class.java).apply { this.action = action }
        startService(intent)
    }

    private fun observeServiceState() {
        val service = workoutService ?: return

        lifecycleScope.launch {
            service.uiState.collectLatest { state ->
                if (state.isFinished) {
                    finish()
                    return@collectLatest
                }

                val exerciseName = state.exerciseName.ifBlank { "—" }

                binding.exerciseLabel.text = exerciseName
                binding.progressRing.setProgress(state.progress)

                val ringColor = when (state.mode) {
                    WorkoutService.Mode.REST -> Color.parseColor("#FF9100") // Orange
                    else -> Color.parseColor("#00E5FF") // Cyan
                }
                binding.progressRing.setForegroundColor(ringColor)

                binding.doneButton.text = when (state.mode) {
                    WorkoutService.Mode.WORK -> "DONE"
                    WorkoutService.Mode.REST -> "SKIP"
                    WorkoutService.Mode.IDLE -> "DONE"
                }
                binding.doneButton.visibility = View.VISIBLE
            }
        }
    }
}
