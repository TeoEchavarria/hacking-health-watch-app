package com.example.sensorstreamerwearos.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * ML-based rep detector using TensorFlow Lite.
 * 
 * Uses a sliding window of IMU data (accelerometer + gyroscope) to detect
 * exercise repetitions in real-time.
 * 
 * Fallback: If no TFLite model is available, uses threshold-based peak detection
 * on acceleration magnitude.
 */
class RepDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "RepDetector"
        private const val MODEL_FILENAME = "rep_counter.tflite"
        
        // Inference window: 2 seconds at 50Hz = 100 samples
        private const val WINDOW_SIZE = 100
        private const val FEATURES_PER_SAMPLE = 6 // accelX, accelY, accelZ, gyroX, gyroY, gyroZ
        
        // Debounce: minimum time between detected reps (400ms)
        private const val MIN_REP_INTERVAL_MS = 400L
        
        // Threshold-based fallback parameters
        private const val ACCEL_MAGNITUDE_THRESHOLD = 12.0f // m/s² (gravity ~9.8)
        private const val PEAK_COOLDOWN_SAMPLES = 20 // 400ms at 50Hz
    }
    
    private var interpreter: Interpreter? = null
    private var useFallback = false
    
    // Sliding window buffer
    private val sensorBuffer = FloatArray(WINDOW_SIZE * FEATURES_PER_SAMPLE)
    private var bufferIndex = 0
    private var bufferFilled = false
    
    // Debounce state
    private var lastRepTime = 0L
    
    // Fallback peak detection state
    private var samplesSincePeak = PEAK_COOLDOWN_SAMPLES
    private var lastMagnitude = 0f
    private var wasAboveThreshold = false
    
    // Listener for rep detection events
    var onRepDetected: (() -> Unit)? = null
    
    init {
        loadModel()
    }
    
    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
            Log.i(TAG, "TFLite model loaded successfully")
            useFallback = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load TFLite model, using threshold fallback: ${e.message}")
            useFallback = true
        }
    }
    
    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILENAME)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Feed sensor data into the detector.
     * Call this at ~50Hz with combined accelerometer and gyroscope data.
     * 
     * @param timestamp System.currentTimeMillis()
     * @param accel [x, y, z] accelerometer values in m/s²
     * @param gyro [x, y, z] gyroscope values in rad/s
     */
    fun onSensorData(timestamp: Long, accel: FloatArray, gyro: FloatArray) {
        // Add to sliding window buffer
        val offset = bufferIndex * FEATURES_PER_SAMPLE
        sensorBuffer[offset] = accel[0]
        sensorBuffer[offset + 1] = accel[1]
        sensorBuffer[offset + 2] = accel[2]
        sensorBuffer[offset + 3] = gyro[0]
        sensorBuffer[offset + 4] = gyro[1]
        sensorBuffer[offset + 5] = gyro[2]
        
        bufferIndex = (bufferIndex + 1) % WINDOW_SIZE
        if (bufferIndex == 0) {
            bufferFilled = true
        }
        
        // Check for rep
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRepTime < MIN_REP_INTERVAL_MS) {
            return // Still in debounce period
        }
        
        val repDetected = if (useFallback) {
            detectRepThreshold(accel)
        } else {
            detectRepML()
        }
        
        if (repDetected) {
            lastRepTime = currentTime
            Log.i(TAG, "REP_DETECTED at $timestamp")
            onRepDetected?.invoke()
        }
    }
    
    /**
     * ML-based detection using TFLite model.
     */
    private fun detectRepML(): Boolean {
        if (!bufferFilled || interpreter == null) return false
        
        // Prepare input tensor: reshape buffer into [1, WINDOW_SIZE, FEATURES_PER_SAMPLE]
        val inputBuffer = ByteBuffer.allocateDirect(WINDOW_SIZE * FEATURES_PER_SAMPLE * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        // Copy buffer starting from current index (oldest sample)
        for (i in 0 until WINDOW_SIZE) {
            val idx = (bufferIndex + i) % WINDOW_SIZE
            val offset = idx * FEATURES_PER_SAMPLE
            for (j in 0 until FEATURES_PER_SAMPLE) {
                inputBuffer.putFloat(sensorBuffer[offset + j])
            }
        }
        inputBuffer.rewind()
        
        // Output: single float probability
        val outputBuffer = ByteBuffer.allocateDirect(4)
        outputBuffer.order(ByteOrder.nativeOrder())
        
        try {
            interpreter?.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val repProbability = outputBuffer.float
            
            // Threshold at 0.5 for binary classification
            return repProbability > 0.5f
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Threshold-based fallback detection using acceleration magnitude peaks.
     * Detects when acceleration crosses above threshold and then falls below.
     */
    private fun detectRepThreshold(accel: FloatArray): Boolean {
        samplesSincePeak++
        
        // Calculate magnitude
        val magnitude = sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2])
        
        val isAboveThreshold = magnitude > ACCEL_MAGNITUDE_THRESHOLD
        
        // Detect falling edge (crossing below threshold after being above)
        val detected = wasAboveThreshold && !isAboveThreshold && 
                       samplesSincePeak >= PEAK_COOLDOWN_SAMPLES
        
        if (detected) {
            samplesSincePeak = 0
        }
        
        wasAboveThreshold = isAboveThreshold
        lastMagnitude = magnitude
        
        return detected
    }
    
    /**
     * Reset detector state. Call when starting a new set.
     */
    fun reset() {
        bufferIndex = 0
        bufferFilled = false
        lastRepTime = 0L
        samplesSincePeak = PEAK_COOLDOWN_SAMPLES
        wasAboveThreshold = false
        sensorBuffer.fill(0f)
        Log.d(TAG, "RepDetector reset")
    }
    
    /**
     * Release resources.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "RepDetector closed")
    }
}
