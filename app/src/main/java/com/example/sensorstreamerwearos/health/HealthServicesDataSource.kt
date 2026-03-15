package com.example.sensorstreamerwearos.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Implementation of [WatchHealthDataSource] using Wear OS Health Services.
 * 
 * This implementation uses PassiveMonitoringClient for battery-efficient
 * background health data collection on Galaxy Watch 6 (Wear OS 4+).
 * 
 * Supports:
 * - Steps (daily aggregated)
 * - Heart Rate (passive samples)
 * - Sleep (via user activity state changes)
 */
class HealthServicesDataSource(
    private val context: Context
) : WatchHealthDataSource {
    
    companion object {
        private const val TAG = "HealthServicesDataSrc"
    }
    
    private val healthServicesClient: HealthServicesClient by lazy {
        HealthServices.getClient(context)
    }
    
    private val passiveMonitoringClient: PassiveMonitoringClient by lazy {
        healthServicesClient.passiveMonitoringClient
    }
    
    // Internal flows for broadcasting data
    private val _heartRateFlow = MutableSharedFlow<HeartRateSample>(replay = 1, extraBufferCapacity = 10)
    private val _stepsFlow = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 5)
    
    // Cache for latest values
    @Volatile private var latestHeartRate: HeartRateSample? = null
    @Volatile private var todaySteps: Int = 0
    @Volatile private var todaySleepMinutes: Int? = null
    @Volatile private var isMonitoringRegistered = false
    
    // Sleep state tracking
    @Volatile private var sleepStartTimestamp: Long? = null
    @Volatile private var lastSleepDate: String? = null
    
    override suspend fun isAvailable(): Boolean {
        return try {
            val capabilities = passiveMonitoringClient.getCapabilitiesAsync().await()
            val hasHeartRate = DataType.HEART_RATE_BPM in capabilities.supportedDataTypesPassiveMonitoring
            val hasSteps = DataType.STEPS_DAILY in capabilities.supportedDataTypesPassiveMonitoring
            Log.d(TAG, "Health Services available: HR=$hasHeartRate, Steps=$hasSteps")
            hasHeartRate && hasSteps
        } catch (e: Exception) {
            Log.e(TAG, "Error checking availability", e)
            false
        }
    }
    
    override suspend fun getTodaySteps(): Int? {
        return if (todaySteps > 0) todaySteps else null
    }
    
    override suspend fun getLatestHeartRate(): HeartRateSample? {
        return latestHeartRate
    }
    
    override suspend fun getTodaySleepMinutes(): Int? {
        return todaySleepMinutes
    }
    
    override fun heartRateFlow(): Flow<HeartRateSample> = _heartRateFlow
    
    override fun dailyStepsFlow(): Flow<Int> = _stepsFlow
    
    override suspend fun registerPassiveMonitoring() {
        if (isMonitoringRegistered) {
            Log.d(TAG, "Passive monitoring already registered")
            return
        }
        
        try {
            val capabilities = passiveMonitoringClient.getCapabilitiesAsync().await()
            
            // Build config with supported data types
            val dataTypes = mutableSetOf<DataType<*, *>>()
            
            if (DataType.HEART_RATE_BPM in capabilities.supportedDataTypesPassiveMonitoring) {
                dataTypes.add(DataType.HEART_RATE_BPM)
            }
            if (DataType.STEPS_DAILY in capabilities.supportedDataTypesPassiveMonitoring) {
                dataTypes.add(DataType.STEPS_DAILY)
            }
            
            if (dataTypes.isEmpty()) {
                Log.e(TAG, "No supported data types for passive monitoring")
                return
            }
            
            val config = PassiveListenerConfig.builder()
                .setDataTypes(dataTypes)
                .setShouldUserActivityInfoBeRequested(true) // For sleep detection
                .build()
            
            passiveMonitoringClient.setPassiveListenerCallback(
                config,
                passiveListenerCallback
            )
            
            isMonitoringRegistered = true
            Log.i(TAG, "Passive monitoring registered for: $dataTypes")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register passive monitoring", e)
            throw e
        }
    }
    
    override suspend fun unregisterPassiveMonitoring() {
        if (!isMonitoringRegistered) {
            return
        }
        
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            isMonitoringRegistered = false
            Log.i(TAG, "Passive monitoring unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister passive monitoring", e)
        }
    }
    
    override suspend fun isPassiveMonitoringRegistered(): Boolean {
        return isMonitoringRegistered
    }
    
    private val passiveListenerCallback = object : PassiveListenerCallback {
        
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            Log.d(TAG, "Received new data points")
            Log.d(TAG, "[DIAGNOSTIC] DataPoints received - HR count: ${dataPoints.getData(DataType.HEART_RATE_BPM).size}, Steps count: ${dataPoints.getData(DataType.STEPS_DAILY).size}")
            
            // Process heart rate samples
            dataPoints.getData(DataType.HEART_RATE_BPM).forEach { dataPoint ->
                val bpm = dataPoint.value.toInt()
                val timestamp = dataPoint.timeDurationFromBoot.toMillis() + 
                    (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())
                
                val sample = HeartRateSample(
                    bpm = bpm,
                    timestamp = timestamp,
                    accuracy = mapAccuracy(dataPoint.accuracy)
                )
                
                latestHeartRate = sample
                _heartRateFlow.tryEmit(sample)
                Log.d(TAG, "HR sample: $bpm bpm")
                Log.i(TAG, "[HEART_RATE][WATCH][COLLECTED] bpm=$bpm, timestamp=$timestamp, accuracy=${sample.accuracy}")
            }
            
            // Process daily steps
            dataPoints.getData(DataType.STEPS_DAILY).forEach { dataPoint ->
                val steps = dataPoint.value.toInt()
                val currentDate = getTodayDateString()
                todaySteps = steps
                _stepsFlow.tryEmit(steps)
                Log.d(TAG, "Steps update: $steps")
                Log.i(TAG, "[STEPS][WATCH][COLLECTED] value=$steps, date=$currentDate, timestamp=${System.currentTimeMillis()}")
                
                if (steps == 0) {
                    Log.w(TAG, "[STEPS][WATCH][COLLECTED] value=0 - no movement detected or Health Services not tracking")
                }
            }
        }
        
        override fun onUserActivityInfoReceived(info: UserActivityInfo) {
            Log.d(TAG, "User activity: ${info.userActivityState}")
            
            val currentTimestamp = System.currentTimeMillis()
            val currentDate = getTodayDateString()
            
            // Track sleep via user activity state with duration calculation
            when (info.userActivityState) {
                UserActivityState.USER_ACTIVITY_ASLEEP -> {
                    // User just fell asleep - record start timestamp
                    Log.i(TAG, "User fell asleep at $currentTimestamp")
                    Log.i(TAG, "[SLEEP][WATCH][COLLECTED] state=ASLEEP_START, date=$currentDate, timestamp=$currentTimestamp")
                    
                    // If it's a new day, reset sleep tracking
                    if (currentDate != lastSleepDate) {
                        if (todaySleepMinutes != null) {
                            Log.i(TAG, "[SLEEP][WATCH][COLLECTED] date_boundary, previous_total=${todaySleepMinutes}min")
                        }
                        todaySleepMinutes = null
                        lastSleepDate = currentDate
                    }
                    
                    sleepStartTimestamp = currentTimestamp
                }
                UserActivityState.USER_ACTIVITY_PASSIVE -> {
                    // User woke up - calculate sleep duration if we have start timestamp
                    Log.i(TAG, "SLEEP_STATE transition: PASSIVE (awake), timestamp=$currentTimestamp")
                    if (sleepStartTimestamp != null) {
                        val sleepDurationMs = currentTimestamp - sleepStartTimestamp!!
                        val sleepDurationMinutes = (sleepDurationMs / 60_000).toInt()
                        
                        // Only count sleep sessions longer than 10 minutes to avoid false positives
                        if (sleepDurationMinutes >= 10) {
                            // Add to today's total sleep (handles multiple naps/sleep sessions)
                            val previousTotal = todaySleepMinutes ?: 0
                            todaySleepMinutes = previousTotal + sleepDurationMinutes
                            
                            val hours = sleepDurationMinutes / 60.0
                            Log.i(TAG, "User woke up. Sleep session: ${String.format("%.1f", hours)} hours ($sleepDurationMinutes min). Total today: $todaySleepMinutes min")
                            Log.i(TAG, "[SLEEP][WATCH][COLLECTED] state=AWAKE, session_duration=${sleepDurationMinutes}min, total_today=${todaySleepMinutes}min, date=$currentDate")
                        } else {
                            Log.d(TAG, "Ignoring short sleep session: $sleepDurationMinutes min")
                            Log.w(TAG, "[SLEEP][WATCH][COLLECTED] state=REJECTED, duration=${sleepDurationMinutes}min, reason=below_10min_threshold")
                        }
                        
                        sleepStartTimestamp = null
                    } else {
                        Log.d(TAG, "User passive/awake (no sleep start timestamp)")
                    }
                }
                UserActivityState.USER_ACTIVITY_EXERCISE -> {
                    Log.d(TAG, "User exercising")
                    
                    // If we were tracking sleep, finalize it (interrupted by exercise)
                    if (sleepStartTimestamp != null) {
                        val sleepDurationMs = currentTimestamp - sleepStartTimestamp!!
                        val sleepDurationMinutes = (sleepDurationMs / 60_000).toInt()
                        
                        if (sleepDurationMinutes >= 10) {
                            todaySleepMinutes = (todaySleepMinutes ?: 0) + sleepDurationMinutes
                            Log.i(TAG, "Sleep interrupted by exercise. Session: $sleepDurationMinutes min. Total: $todaySleepMinutes min")
                        }
                        
                        sleepStartTimestamp = null
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown activity state")
                    
                    // If we were tracking sleep, finalize it
                    if (sleepStartTimestamp != null) {
                        val sleepDurationMs = currentTimestamp - sleepStartTimestamp!!
                        val sleepDurationMinutes = (sleepDurationMs / 60_000).toInt()
                        
                        if (sleepDurationMinutes >= 10) {
                            todaySleepMinutes = (todaySleepMinutes ?: 0) + sleepDurationMinutes
                            Log.i(TAG, "Sleep interrupted. Session: $sleepDurationMinutes min. Total: $todaySleepMinutes min")
                        }
                        
                        sleepStartTimestamp = null
                    }
                }
            }
        }
        
        override fun onPermissionLost() {
            Log.w(TAG, "Health Services permission lost")
            Log.e(TAG, "PERMISSION_LOST: Health Services passive monitoring permission revoked - data collection stopped")
            isMonitoringRegistered = false
        }
    }
    
    private fun mapAccuracy(accuracy: androidx.health.services.client.data.DataPointAccuracy?): HeartRateAccuracy {
        return when (accuracy) {
            is androidx.health.services.client.data.HeartRateAccuracy -> {
                when (accuracy.sensorStatus) {
                    androidx.health.services.client.data.HeartRateAccuracy.SensorStatus.ACCURACY_HIGH -> HeartRateAccuracy.HIGH
                    androidx.health.services.client.data.HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM -> HeartRateAccuracy.MEDIUM
                    androidx.health.services.client.data.HeartRateAccuracy.SensorStatus.ACCURACY_LOW -> HeartRateAccuracy.LOW
                    else -> HeartRateAccuracy.UNKNOWN
                }
            }
            else -> HeartRateAccuracy.UNKNOWN
        }
    }
    
    /**
     * Get today's date as ISO string.
     */
    fun getTodayDateString(): String {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    
    /**
     * Build a daily summary from cached data.
     */
    fun buildDailySummary(heartRateSamples: List<HeartRateSample>): HealthDailySummary {
        val avgHr = if (heartRateSamples.isNotEmpty()) {
            heartRateSamples.map { it.bpm }.average().toInt()
        } else null
        
        val minHr = heartRateSamples.minOfOrNull { it.bpm }
        val maxHr = heartRateSamples.maxOfOrNull { it.bpm }
        
        return HealthDailySummary(
            date = getTodayDateString(),
            steps = todaySteps,
            sleepMinutes = todaySleepMinutes,
            heartRateSamples = heartRateSamples,
            avgHeartRate = avgHr,
            minHeartRate = minHr,
            maxHeartRate = maxHr
        )
    }
}
