package com.example.sensorstreamerwearos.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private var lastVerificationTime = 0L
    private const val TIMEOUT_MS = 10000L // 10 seconds timeout for handshake

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

    fun setVerified() {
        Log.d(TAG, "✅ Connection VERIFIED via PONG")
        updateState(State.VERIFIED)
        lastVerificationTime = System.currentTimeMillis()
    }

    fun setVerifying() {
        Log.d(TAG, "⏳ Handshake in progress...")
        updateState(State.VERIFYING)
    }

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
        listeners.forEach { it.onStateChanged(state) }
    }
    
    fun isVerified(): Boolean {
        return _connectionState.value == State.VERIFIED
    }
}
