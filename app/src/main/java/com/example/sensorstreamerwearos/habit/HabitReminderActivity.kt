package com.example.sensorstreamerwearos.habit

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
import com.example.sensorstreamerwearos.databinding.ActivityHabitReminderBinding
import com.example.sensorstreamerwearos.habit.HabitService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen habit reminder UI. Similar to WorkoutTimerActivity but simpler.
 * - Large habit title
 * - Circular progress ring (decreases as time passes)
 * - DONE button
 * - Swipe left/right = postpone
 */
class HabitReminderActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HabitReminder"
        private const val GESTURE_TAG = "HabitGesture"
        private const val SWIPE_THRESHOLD_DP = 60
        private const val SWIPE_VELOCITY_THRESHOLD = 100
    }

    private lateinit var binding: ActivityHabitReminderBinding
    private var habitService: HabitService? = null
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
                absDx >= swipeThresholdPx && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD -> {
                    Log.i(GESTURE_TAG, "Swipe detected → POSTPONE")
                    performPostpone()
                    true
                }
                else -> false
            }
        }
    }

    private lateinit var gestureDetector: GestureDetectorCompat

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HabitService.LocalBinder
            habitService = binder.getService()
            isBound = true
            Log.i(TAG, "HabitReminderActivity bound to HabitService")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            habitService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHabitReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        swipeThresholdPx = (SWIPE_THRESHOLD_DP * resources.displayMetrics.density).toInt()
        gestureDetector = GestureDetectorCompat(this, gestureListener)

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        binding.doneButton.setOnClickListener { performDone() }

        val serviceIntent = Intent(this, HabitService::class.java)
        val titleFromIntent = getIntent().getStringExtra(HabitService.EXTRA_TITLE) ?: "Habit"
        binding.habitTitle.text = titleFromIntent
        binding.progressRing.setProgress(1f)
        binding.progressRing.setForegroundColor(Color.parseColor("#00E5FF"))

        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun performDone() {
        val serviceIntent = Intent(this, HabitService::class.java).apply {
            action = HabitService.ACTION_DONE
            putExtra(HabitService.EXTRA_HABIT_ID, this@HabitReminderActivity.intent.getStringExtra(HabitService.EXTRA_HABIT_ID))
            putExtra(HabitService.EXTRA_TITLE, this@HabitReminderActivity.intent.getStringExtra(HabitService.EXTRA_TITLE))
            putExtra(HabitService.EXTRA_SOURCE_NODE_ID, this@HabitReminderActivity.intent.getStringExtra(HabitService.EXTRA_SOURCE_NODE_ID))
        }
        startService(serviceIntent)
        finish()
    }

    private fun performPostpone() {
        val serviceIntent = Intent(this, HabitService::class.java).apply {
            action = HabitService.ACTION_POSTPONE
            putExtra(HabitService.EXTRA_HABIT_ID, this@HabitReminderActivity.intent.getStringExtra(HabitService.EXTRA_HABIT_ID))
            putExtra(HabitService.EXTRA_TITLE, this@HabitReminderActivity.intent.getStringExtra(HabitService.EXTRA_TITLE))
            putExtra(HabitService.EXTRA_SOURCE_NODE_ID, this@HabitReminderActivity.intent.getStringExtra(HabitService.EXTRA_SOURCE_NODE_ID))
        }
        startService(serviceIntent)
        finish()
    }

    private fun observeServiceState() {
        val service = habitService
        if (service != null) {
            lifecycleScope.launch {
                service.uiState.collectLatest { state ->
                    binding.habitTitle.text = state.title.ifBlank { "Habit" }
                    binding.progressRing.setProgress(state.progress)
                    binding.progressRing.setForegroundColor(Color.parseColor("#00E5FF"))
                }
            }
        } else {
            val title = intent.getStringExtra(HabitService.EXTRA_TITLE) ?: "Habit"
            binding.habitTitle.text = title
            binding.progressRing.setProgress(1f)
        }
    }
}
