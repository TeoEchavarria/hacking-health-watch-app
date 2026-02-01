package com.example.sensorstreamerwearos.workout.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.sensorstreamerwearos.databinding.ActivityWorkoutTimerBinding
import com.example.sensorstreamerwearos.workout.service.WorkoutService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen workout timer. Shows exercise name (large), WORK/REST mode, mm:ss timer.
 * Binds to WorkoutService and observes WorkoutUiState. UI only, no timer logic.
 */
class WorkoutTimerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WorkoutUI"
    }

    private lateinit var binding: ActivityWorkoutTimerBinding
    private var workoutService: WorkoutService? = null
    private var isBound = false

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

        val sessionId = intent.getStringExtra("sessionId")
        Log.i(TAG, "WorkoutTimerActivity onCreate sessionId=$sessionId")

        binding.doneButton.setOnClickListener { sendAction(WorkoutService.ACTION_DONE_SET) }
        binding.skipRestButton.setOnClickListener { sendAction(WorkoutService.ACTION_SKIP_REST) }
        binding.stopButton.setOnClickListener {
            sendAction(WorkoutService.ACTION_STOP)
            finish()
        }

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

    private fun sendAction(action: String) {
        val intent = Intent(this, WorkoutService::class.java).apply { this.action = action }
        startService(intent)
    }

    private fun observeServiceState() {
        val service = workoutService ?: return

        lifecycleScope.launch {
            service.uiState.collectLatest { state ->
                val exerciseName = state.exerciseName.ifBlank { "—" }
                val modeStr = when (state.mode) {
                    WorkoutService.Mode.WORK -> "WORK"
                    WorkoutService.Mode.REST -> "REST"
                    WorkoutService.Mode.IDLE -> ""
                }
                val timeStr = when (state.mode) {
                    WorkoutService.Mode.WORK -> String.format("%02d:%02d", state.workElapsedSec / 60, state.workElapsedSec % 60)
                    WorkoutService.Mode.REST -> String.format("%02d:%02d", state.restRemainingSec / 60, state.restRemainingSec % 60)
                    WorkoutService.Mode.IDLE -> "00:00"
                }
                Log.i(TAG, "Rendering state: $modeStr $exerciseName $timeStr")
                binding.exerciseLabel.text = exerciseName
                binding.modeLabel.text = modeStr
                binding.setProgress.text = "Set ${state.setIndex} / ${state.totalSets}"

                binding.timerText.text = timeStr

                binding.doneButton.visibility =
                    if (state.mode == WorkoutService.Mode.WORK && !state.isPaused) View.VISIBLE else View.GONE
                binding.skipRestButton.visibility =
                    if (state.mode == WorkoutService.Mode.REST) View.VISIBLE else View.GONE
                binding.stopButton.visibility = if (!state.isFinished) View.VISIBLE else View.GONE
                binding.finishedText.visibility = if (state.isFinished) View.VISIBLE else View.GONE
            }
        }
    }
}
