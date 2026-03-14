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
            return
        }
        
        isRunning = true
        Log.i(TAG, "Starting HealthSyncManager")
        
        scope.launch {
            try {
                // Check availability
                if (!healthDataSource.isAvailable()) {
                    Log.e(TAG, "Health Services not available on this device")
                    return@launch
                }
                
                // Register passive monitoring
                healthDataSource.registerPassiveMonitoring()
                
                // Start collecting HR samples
                startHrCollection()
                
                // Start periodic sync
                startPeriodicSync()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start", e)
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
            while (isActive && isRunning) {
                try {
                    syncDailySummary()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic sync", e)
                }
                delay(DAILY_SYNC_INTERVAL_MS)
            }
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
        
        try {
            val nodeId = findPhoneNode() ?: run {
                Log.w(TAG, "No phone node found")
                return
            }
            
            val jsonString = json.encodeToString(summary)
            val byteArray = jsonString.toByteArray(Charsets.UTF_8)
            
            val putDataMapReq = PutDataMapRequest.create(Protocol.PATH_HEALTH_DAILY)
            putDataMapReq.dataMap.putByteArray("summary", byteArray)
            putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
            
            val putDataReq = putDataMapReq.asPutDataRequest()
            putDataReq.setUrgent()
            
            dataClient.putDataItem(putDataReq).await()
            
            Log.i(TAG, "Daily summary synced: steps=${summary.steps}, hr_samples=${summary.heartRateSamples.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync daily summary", e)
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
