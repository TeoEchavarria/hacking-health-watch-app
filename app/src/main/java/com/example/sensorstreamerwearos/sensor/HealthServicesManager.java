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
    private static final long SAMPLE_INTERVAL_MS = 1000; // Collect 1 sample per second
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorDataListener listener;
    private long lastSampleTime = 0;
    private String deviceId;

    public interface SensorDataListener {
        void onSensorData(SensorData data);
    }

    public HealthServicesManager(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        deviceId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                Log.e(TAG, "Accelerometer sensor not available");
            }
        } else {
            Log.e(TAG, "SensorManager not available");
        }
    }

    public void setListener(SensorDataListener listener) {
        this.listener = listener;
    }

    public void startAccelerometerMonitoring() {
        if (sensorManager == null || accelerometer == null) {
            Log.e(TAG, "Cannot start monitoring - sensor not available");
            return;
        }

        Log.d(TAG, "Starting Accelerometer monitoring");
        // SENSOR_DELAY_NORMAL = ~200ms (~5Hz), SENSOR_DELAY_GAME = ~20ms (~50Hz)
        // Using NORMAL for battery efficiency - still gives us ~5 samples/second
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void stopAccelerometerMonitoring() {
        if (sensorManager != null) {
            Log.d(TAG, "Stopping Accelerometer monitoring");
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            long currentTime = System.currentTimeMillis();
            
            // Only collect 1 sample per second for batching
            if (currentTime - lastSampleTime >= SAMPLE_INTERVAL_MS) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                
                if (listener != null) {
                    listener.onSensorData(new SensorData(deviceId, "accel", currentTime, new float[]{x, y, z}));
                }
                
                lastSampleTime = currentTime;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed: " + accuracy);
    }
}
