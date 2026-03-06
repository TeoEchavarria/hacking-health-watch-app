package com.example.sensorstreamerwearos.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that manages the connection state between Watch and Phone.
 * 
 * Connection is verified when:
 * 1. Phone responds with PONG to our PING (via MessageClient)
 * 2. Phone sends /handshake_ack DataItem
 * 3. Phone publishes /state/phone with READY state or valid ACK sequence
 * 
 * Multiple services update this state:
 * - WatchReceiverService (WearableListenerService - background)
 * - SensorForegroundService (active listeners)
 * - WatchConnectivityController (channel streaming)
 * 
 * Thread-safety is ensured via StateFlow and synchronized state updates.
 */
object ConnectionManager {
    private const val TAG = "ConnectionManager"
    
    // Handshake state
    enum class State {
        UNVERIFIED,
        VERIFYING,
        VERIFIED
    }

    private val _connectionState = MutableStateFlow(State.UNVERIFIED)
    val connectionState: StateFlow<State> = _connectionState.asStateFlow()

    @Volatile
    private var lastVerificationTime = 0L
    private const val TIMEOUT_MS = 10000L // 10 seconds timeout for handshake
    private const val VERIFICATION_EXPIRY_MS = 60000L // Consider unverified after 60s of no updates

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ConnectionStateListener>()

    interface ConnectionStateListener {
        fun onStateChanged(state: State)
    }

    fun addListener(listener: ConnectionStateListener) {
        listeners.add(listener)
        listener.onStateChanged(_connectionState.value)
    }

    fun removeListener(listener: ConnectionStateListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun setVerified() {
        val now = System.currentTimeMillis()
        Log.d(TAG, "✅ Connection VERIFIED (source: external trigger)")
        lastVerificationTime = now
        updateState(State.VERIFIED)
    }

    @Synchronized
    fun setVerifying() {
        Log.d(TAG, "⏳ Handshake in progress...")
        updateState(State.VERIFYING)
    }

    @Synchronized
    fun setUnverified() {
        Log.d(TAG, "❌ Connection UNVERIFIED")
        updateState(State.UNVERIFIED)
    }

    private fun updateState(newState: State) {
        if (_connectionState.value != newState) {
            _connectionState.value = newState
            notifyListeners(newState)
        }
    }

    private fun notifyListeners(state: State) {
        listeners.forEach { 
            try {
                it.onStateChanged(state) 
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    fun isVerified(): Boolean {
        val state = _connectionState.value
        if (state != State.VERIFIED) return false
        
        // Check if verification has expired
        val now = System.currentTimeMillis()
        if (now - lastVerificationTime > VERIFICATION_EXPIRY_MS) {
            Log.w(TAG, "Connection verification expired - marking as UNVERIFIED")
            setUnverified()
            return false
        }
        return true
    }
    
    /**
     * Touch the verification timestamp without changing state.
     * Call this when receiving any valid data from phone.
     */
    @Synchronized
    fun touchVerification() {
        if (_connectionState.value == State.VERIFIED) {
            lastVerificationTime = System.currentTimeMillis()
        }
    }
}
