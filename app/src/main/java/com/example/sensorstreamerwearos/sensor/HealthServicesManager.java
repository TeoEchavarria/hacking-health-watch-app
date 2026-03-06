package com.example.sensorstreamerwearos.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import com.example.sensorstreamerwearos.model.SensorData;

public class HealthServicesManager implements SensorEventListener {
    private static final String TAG = "HealthServicesManager";
    private static final long SAMPLE_INTERVAL_MS = 1000; // Collect 1 sample per second (normal mode)
    private static final long WORKOUT_SAMPLE_INTERVAL_MS = 20; // 50Hz for workout mode
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private SensorDataListener listener;
    private WorkoutSensorListener workoutListener;
    private long lastSampleTime = 0;
    private long lastWorkoutSampleTime = 0;
    private String deviceId;
    private boolean isWorkoutMode = false;
    
    // Latest sensor values for combining accel + gyro
    private float[] latestAccel = new float[3];
    private float[] latestGyro = new float[3];
    private long lastAccelTime = 0;
    private long lastGyroTime = 0;

    public interface SensorDataListener {
        void onSensorData(SensorData data);
    }
    
    /**
     * Listener for high-frequency workout sensor data (6D IMU: accel + gyro).
     * Called at ~50Hz during workout mode.
     */
    public interface WorkoutSensorListener {
        /**
         * @param timestamp System.currentTimeMillis()
         * @param accel [x, y, z] in m/s²
         * @param gyro [x, y, z] in rad/s
         */
        void onWorkoutSensorData(long timestamp, float[] accel, float[] gyro);
    }

    public HealthServicesManager(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        deviceId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (accelerometer == null) {
                Log.e(TAG, "Accelerometer sensor not available");
            }
            if (gyroscope == null) {
                Log.w(TAG, "Gyroscope sensor not available - rep detection may be less accurate");
            }
        } else {
            Log.e(TAG, "SensorManager not available");
        }
    }

    public void setListener(SensorDataListener listener) {
        this.listener = listener;
    }
    
    public void setWorkoutListener(WorkoutSensorListener listener) {
        this.workoutListener = listener;
    }
    
    /**
     * Enable/disable high-frequency workout mode for rep detection.
     * In workout mode, sensors run at 50Hz and emit combined IMU data.
     */
    public void setWorkoutMode(boolean enabled) {
        if (isWorkoutMode == enabled) return;
        
        isWorkoutMode = enabled;
        Log.i(TAG, "Workout mode " + (enabled ? "ENABLED (50Hz)" : "DISABLED (1Hz)"));
        
        // Re-register sensors with new rate
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            
            int delay = enabled ? SensorManager.SENSOR_DELAY_GAME : SensorManager.SENSOR_DELAY_NORMAL;
            
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, delay);
            }
            if (enabled && gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, delay);
            }
        }
    }
    
    public boolean isWorkoutMode() {
        return isWorkoutMode;
    }

    public void startAccelerometerMonitoring() {
        if (sensorManager == null || accelerometer == null) {
            Log.e(TAG, "Cannot start monitoring - sensor not available");
            return;
        }

        Log.i(TAG, "=== Starting Accelerometer monitoring ===");
        // SENSOR_DELAY_NORMAL = ~200ms (~5Hz), SENSOR_DELAY_GAME = ~20ms (~50Hz)
        // Using NORMAL for battery efficiency - still gives us ~5 samples/second
        int delay = isWorkoutMode ? SensorManager.SENSOR_DELAY_GAME : SensorManager.SENSOR_DELAY_NORMAL;
        sensorManager.registerListener(this, accelerometer, delay);
        
        // Also register gyroscope if in workout mode
        if (isWorkoutMode && gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, delay);
        }
    }

    public void stopAccelerometerMonitoring() {
        if (sensorManager != null) {
            Log.d(TAG, "Stopping Accelerometer monitoring");
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long currentTime = System.currentTimeMillis();
        
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            latestAccel[0] = event.values[0];
            latestAccel[1] = event.values[1];
            latestAccel[2] = event.values[2];
            lastAccelTime = currentTime;
            
            // Normal mode: throttle to 1Hz for batching
            if (!isWorkoutMode) {
                if (currentTime - lastSampleTime >= SAMPLE_INTERVAL_MS) {
                    Log.i(TAG, "WATCH_ACCEL_SAMPLE: x=" + String.format("%.2f", latestAccel[0]) + 
                          ", y=" + String.format("%.2f", latestAccel[1]) + 
                          ", z=" + String.format("%.2f", latestAccel[2]));
                    
                    if (listener != null) {
                        listener.onSensorData(new SensorData(deviceId, "accel", currentTime, 
                            new float[]{latestAccel[0], latestAccel[1], latestAccel[2]}));
                    }
                    lastSampleTime = currentTime;
                }
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            latestGyro[0] = event.values[0];
            latestGyro[1] = event.values[1];
            latestGyro[2] = event.values[2];
            lastGyroTime = currentTime;
        }
        
        // Workout mode: emit combined IMU at 50Hz
        if (isWorkoutMode && workoutListener != null) {
            if (currentTime - lastWorkoutSampleTime >= WORKOUT_SAMPLE_INTERVAL_MS) {
                workoutListener.onWorkoutSensorData(currentTime, 
                    new float[]{latestAccel[0], latestAccel[1], latestAccel[2]},
                    new float[]{latestGyro[0], latestGyro[1], latestGyro[2]});
                lastWorkoutSampleTime = currentTime;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed: " + accuracy);
    }
}
