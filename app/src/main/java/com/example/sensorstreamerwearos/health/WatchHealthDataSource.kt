package com.example.sensorstreamerwearos.health

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction layer for watch-side health data collection.
 * 
 * This interface enables the watch app to collect health metrics (steps, heart rate, sleep)
 * without being tightly coupled to a specific data source (Health Services, SensorManager, etc.).
 * 
 * Primary implementation: [HealthServicesDataSource] using Wear OS Health Services.
 */
interface WatchHealthDataSource {
    
    /**
     * Check if health services are available on this device.
     */
    suspend fun isAvailable(): Boolean
    
    /**
     * Get today's step count.
     * Returns null if data is unavailable.
     */
    suspend fun getTodaySteps(): Int?
    
    /**
     * Get the most recent heart rate reading.
     * Returns null if no recent reading is available.
     */
    suspend fun getLatestHeartRate(): HeartRateSample?
    
    /**
     * Get today's sleep duration in minutes.
     * Returns null if sleep data is unavailable.
     */
    suspend fun getTodaySleepMinutes(): Int?
    
    /**
     * Flow of heart rate samples as they become available.
     * Used for real-time heart rate monitoring when needed.
     */
    fun heartRateFlow(): Flow<HeartRateSample>
    
    /**
     * Flow of daily step updates.
     */
    fun dailyStepsFlow(): Flow<Int>
    
    /**
     * Register for passive monitoring of health metrics.
     * This enables background data collection without foreground service.
     */
    suspend fun registerPassiveMonitoring()
    
    /**
     * Unregister passive monitoring.
     */
    suspend fun unregisterPassiveMonitoring()
    
    /**
     * Check if passive monitoring is currently registered.
     */
    suspend fun isPassiveMonitoringRegistered(): Boolean
}
