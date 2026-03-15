package com.example.sensorstreamerwearos.health

import android.content.Context
import android.util.Log
import com.example.sensorstreamerwearos.network.Protocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Orchestrates syncing health data from watch to phone.
 * 
 * Responsibilities:
 * - Collects health data from [WatchHealthDataSource]
 * - Batches and sends data to phone via Wear Data Layer
 * - Handles sync scheduling and retry logic
 * - Maintains sync state across process restarts
 */
class HealthSyncManager(
    private val context: Context,
    private val healthDataSource: WatchHealthDataSource
) {
    companion object {
        private const val TAG = "HealthSyncManager"
        
        // Sync intervals
        private const val DAILY_SYNC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val HR_BATCH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes for HR batches
        private const val MIN_HR_SAMPLES_FOR_SYNC = 5 // Minimum samples before syncing
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }
    
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // HR samples collected since last sync
    private val pendingHrSamples = mutableListOf<HeartRateSample>()
    private val hrSamplesLock = Any()
    
    private var syncJob: Job? = null
    private var hrCollectionJob: Job? = null
    
    @Volatile
    private var isRunning = false
    
    /**
     * Start health sync manager.
     * Registers passive monitoring and begins periodic sync.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "Already running")
            Log.w(TAG, "[DIAGNOSTIC] HealthSyncManager already running")
            return
        }
        
        isRunning = true
        Log.i(TAG, "Starting HealthSyncManager")
        Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTING] HealthSyncManager initializing")
        
        scope.launch {
            try {
                // Check availability
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTING] Checking Health Services availability")
                if (!healthDataSource.isAvailable()) {
                    Log.e(TAG, "Health Services not available on this device")
                    Log.e(TAG, "[DIAGNOSTIC][MANAGER][ERROR] Health Services NOT AVAILABLE - sync will not work")
                    return@launch
                }
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTING] Health Services available")
                
                // Register passive monitoring
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTING] Registering passive monitoring")
                healthDataSource.registerPassiveMonitoring()
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTED] Passive monitoring registered")
                
                // Start collecting HR samples
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTING] Starting HR collection flow")
                startHrCollection()
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTED] HR collection flow started")
                
                // Start periodic sync
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTING] Starting periodic sync job (15min interval)")
                startPeriodicSync()
                Log.i(TAG, "[DIAGNOSTIC][MANAGER][STARTED] Periodic sync job started - next sync in 15min")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start", e)
                Log.e(TAG, "[DIAGNOSTIC][MANAGER][ERROR] Startup failed: ${e.message}")
            }
        }
    }
    
    /**
     * Stop health sync manager.
     */
    fun stop() {
        if (!isRunning) return
        
        isRunning = false
        Log.i(TAG, "Stopping HealthSyncManager")
        
        syncJob?.cancel()
        hrCollectionJob?.cancel()
        
        scope.launch {
            try {
                healthDataSource.unregisterPassiveMonitoring()
            } catch (e: Exception) {
                Log.e(TAG, "Error during stop", e)
            }
        }
    }
    
    private fun startHrCollection() {
        hrCollectionJob = scope.launch {
            healthDataSource.heartRateFlow()
                .collect { sample ->
                    synchronized(hrSamplesLock) {
                        pendingHrSamples.add(sample)
                        Log.d(TAG, "HR sample collected: ${sample.bpm} bpm (total: ${pendingHrSamples.size})")
                    }
                    
                    // Sync if we have enough samples
                    if (pendingHrSamples.size >= MIN_HR_SAMPLES_FOR_SYNC) {
                        syncHeartRateBatch()
                    }
                }
        }
    }
    
    private fun startPeriodicSync() {
        syncJob = scope.launch {
            Log.i(TAG, "[DIAGNOSTIC][SYNC_JOB] Periodic sync job coroutine started")
            var syncCount = 0
            while (isActive && isRunning) {
                try {
                    syncCount++
                    Log.i(TAG, "[DIAGNOSTIC][SYNC_JOB] Executing sync #$syncCount")
                    val startTime = System.currentTimeMillis()
                    syncDailySummary()
                    val duration = System.currentTimeMillis() - startTime
                    Log.i(TAG, "[DIAGNOSTIC][SYNC_JOB] Sync #$syncCount completed in ${duration}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic sync", e)
                    Log.e(TAG, "[DIAGNOSTIC][SYNC_JOB] Sync #$syncCount FAILED: ${e.message}")
                }
                Log.i(TAG, "[DIAGNOSTIC][SYNC_JOB] Waiting ${DAILY_SYNC_INTERVAL_MS / 1000 / 60}min until next sync")
                delay(DAILY_SYNC_INTERVAL_MS)
            }
            Log.w(TAG, "[DIAGNOSTIC][SYNC_JOB] Periodic sync job STOPPED - isActive=$isActive, isRunning=$isRunning")
        }
    }
    
    /**
     * Sync the current daily summary to the phone.
     */
    suspend fun syncDailySummary() {
        Log.d(TAG, "Syncing daily summary...")
        
        val hrSamples: List<HeartRateSample>
        synchronized(hrSamplesLock) {
            hrSamples = pendingHrSamples.toList()
        }
        
        val summary = (healthDataSource as? HealthServicesDataSource)?.buildDailySummary(hrSamples)
            ?: return
        
        // Log detailed metrics before sending
        Log.i(TAG, "DAILY_SUMMARY built: date=${summary.date}, steps=${summary.steps}, sleepMinutes=${summary.sleepMinutes}, hr_samples=${summary.heartRateSamples.size}, avgHR=${summary.avgHeartRate}")
        
        // Forensic per-metric serialization logs
        Log.i(TAG, "[STEPS][WATCH][SERIALIZED] date=${summary.date}, value=${summary.steps}")
        if (summary.sleepMinutes != null) {
            Log.i(TAG, "[SLEEP][WATCH][SERIALIZED] date=${summary.date}, value=${summary.sleepMinutes}min")
        } else {
            Log.w(TAG, "[SLEEP][WATCH][SERIALIZED] date=${summary.date}, value=NULL, reason=no_sleep_session_detected")
        }
        Log.i(TAG, "[HEART_RATE][WATCH][SERIALIZED] date=${summary.date}, sample_count=${summary.heartRateSamples.size}, avgBPM=${summary.avgHeartRate}")
        
        if (summary.steps == 0) {
            Log.w(TAG, "DAILY_SUMMARY contains ZERO steps - may indicate no movement or Health Services not tracking")
        }
        if (summary.sleepMinutes == null) {
            Log.w(TAG, "DAILY_SUMMARY contains NULL sleep - no sleep session detected today or threshold not met")
        } else {
            Log.i(TAG, "DAILY_SUMMARY sleep: ${summary.sleepMinutes} minutes (${summary.sleepMinutes / 60.0} hours)")
        }
        
        try {
            val nodeId = findPhoneNode() ?: run {
                Log.w(TAG, "No phone node found")
                Log.e(TAG, "SEND_FAILED: No phone node reachable - cannot send daily summary")
                return
            }
            
            val jsonString = json.encodeToString(summary)
            val byteArray = jsonString.toByteArray(Charsets.UTF_8)
            
            Log.d(TAG, "SEND_PAYLOAD: path=${Protocol.PATH_HEALTH_DAILY}, size=${byteArray.size} bytes, nodeId=$nodeId")
            
            val putDataMapReq = PutDataMapRequest.create(Protocol.PATH_HEALTH_DAILY)
            putDataMapReq.dataMap.putByteArray("summary", byteArray)
            putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
            
            val putDataReq = putDataMapReq.asPutDataRequest()
            putDataReq.setUrgent()
            
            dataClient.putDataItem(putDataReq).await()
            
            Log.i(TAG, "Daily summary synced: steps=${summary.steps}, hr_samples=${summary.heartRateSamples.size}")
            Log.i(TAG, "SEND_SUCCESS: Daily summary sent to phone successfully")
            
            // Forensic per-metric send confirmation logs
            Log.i(TAG, "[STEPS][WATCH][SENT] date=${summary.date}, value=${summary.steps}, nodeId=$nodeId")
            if (summary.sleepMinutes != null) {
                Log.i(TAG, "[SLEEP][WATCH][SENT] date=${summary.date}, value=${summary.sleepMinutes}min, nodeId=$nodeId")
            } else {
                Log.w(TAG, "[SLEEP][WATCH][SENT] date=${summary.date}, value=NULL, nodeId=$nodeId")
            }
            Log.i(TAG, "[HEART_RATE][WATCH][SENT] date=${summary.date}, sample_count=${summary.heartRateSamples.size}, nodeId=$nodeId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync daily summary", e)
            Log.e(TAG, "SEND_ERROR: ${e.javaClass.simpleName}: ${e.message}")
            
            // Forensic per-metric send failure logs
            Log.e(TAG, "[STEPS][WATCH][SENT] ERROR: ${e.message}")
            Log.e(TAG, "[SLEEP][WATCH][SENT] ERROR: ${e.message}")
            Log.e(TAG, "[HEART_RATE][WATCH][SENT] ERROR: ${e.message}")
        }
    }
    
    /**
     * Sync accumulated heart rate samples as a batch.
     */
    private suspend fun syncHeartRateBatch() {
        val samplesToSync: List<HeartRateSample>
        synchronized(hrSamplesLock) {
            if (pendingHrSamples.isEmpty()) return
            samplesToSync = pendingHrSamples.toList()
            // Don't clear yet - will clear on successful sync
        }
        
        try {
            val nodeId = findPhoneNode() ?: return
            
            val update = HealthIncrementalUpdate(
                type = UpdateType.HEART_RATE,
                timestamp = System.currentTimeMillis(),
                heartRateSample = samplesToSync.lastOrNull()
            )
            
            val jsonString = json.encodeToString(samplesToSync)
            val byteArray = jsonString.toByteArray(Charsets.UTF_8)
            
            val putDataMapReq = PutDataMapRequest.create(Protocol.PATH_HEALTH_HR)
            putDataMapReq.dataMap.putByteArray("samples", byteArray)
            putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
            
            val putDataReq = putDataMapReq.asPutDataRequest()
            putDataReq.setUrgent()
            
            dataClient.putDataItem(putDataReq).await()
            
            // Clear synced samples
            synchronized(hrSamplesLock) {
                pendingHrSamples.clear()
            }
            
            Log.d(TAG, "HR batch synced: ${samplesToSync.size} samples")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync HR batch", e)
        }
    }
    
    /**
     * Force an immediate sync of all pending data.
     */
    suspend fun forceSyncNow() {
        Log.i(TAG, "Force sync requested")
        syncHeartRateBatch()
        syncDailySummary()
    }
    
    private suspend fun findPhoneNode(): String? {
        return try {
            val capabilityInfo = capabilityClient
                .getCapability(Protocol.CAPABILITY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
                .await()
            
            val nodes = capabilityInfo.nodes
            if (nodes.isEmpty()) {
                Log.w(TAG, "No phone nodes with capability found")
                null
            } else {
                val bestNode = nodes.firstOrNull { it.isNearby } ?: nodes.first()
                Log.d(TAG, "Found phone node: ${bestNode.displayName}")
                bestNode.id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding phone node", e)
            null
        }
    }
}
