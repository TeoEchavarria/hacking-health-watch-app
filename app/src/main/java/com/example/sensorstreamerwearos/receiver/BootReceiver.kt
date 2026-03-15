package com.example.sensorstreamerwearos.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.sensorstreamerwearos.service.HealthSyncService

/**
 * Receiver that starts the HealthSyncService when the device boots.
 * This ensures health data is continuously synced with the phone app.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot/Update completed - starting HealthSyncService")
                Log.i(TAG, "[DIAGNOSTIC][BOOT] Boot/Update event received: ${intent.action}")
                startHealthSyncService(context)
            }
        }
    }
    
    private fun startHealthSyncService(context: Context) {
        try {
            Log.i(TAG, "[DIAGNOSTIC][BOOT] Attempting to start HealthSyncService")
            val serviceIntent = Intent(context, HealthSyncService::class.java).apply {
                action = HealthSyncService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "HealthSyncService start requested")
            Log.i(TAG, "[DIAGNOSTIC][BOOT][SUCCESS] HealthSyncService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HealthSyncService", e)
            Log.e(TAG, "[DIAGNOSTIC][BOOT][ERROR] Failed to start HealthSyncService: ${e.message}")
        }
    }
}
