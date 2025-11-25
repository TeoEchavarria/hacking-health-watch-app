package com.example.sensorstreamerwearos.sensor;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import androidx.health.services.client.HealthServices;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.MeasureCallback;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.DeltaDataType;
import androidx.health.services.client.data.SampleDataPoint;
import com.example.sensorstreamerwearos.model.SensorData;
import java.time.Instant;
import java.util.List;

public class HealthServicesManager {
    private static final String TAG = "HealthServicesManager";
    private MeasureClient measureClient;
    private MeasureCallback measureCallback;
    private SensorDataListener listener;

    public interface SensorDataListener {
        void onSensorData(SensorData data);
    }

    public HealthServicesManager(Context context) {
        try {
            measureClient = HealthServices.getClient(context).getMeasureClient();
        } catch (Exception e) {
            Log.e(TAG, "Health Services not available", e);
        }
    }

    public void setListener(SensorDataListener listener) {
        this.listener = listener;
    }

    public void startHeartRateMonitoring() {
        if (measureClient == null) return;

        Log.d(TAG, "Starting HR monitoring");
        measureCallback = new MeasureCallback() {
            @Override
            public void onAvailabilityChanged(DeltaDataType dataType, Availability availability) {
                Log.d(TAG, "Availability changed: " + availability);
            }

            @Override
            public void onDataReceived(DataPointContainer dataPointContainer) {
                List<SampleDataPoint<Double>> dataPoints = dataPointContainer.getData(DataType.HEART_RATE_BPM);
                Instant bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime());
                
                for (SampleDataPoint<Double> point : dataPoints) {
                    double heartRate = point.getValue();
                    long timestamp = point.getTimeInstant(bootInstant).toEpochMilli();
                    Log.d(TAG, "HR: " + heartRate);
                    
                    if (listener != null) {
                        listener.onSensorData(new SensorData(timestamp, "hr", new float[]{(float) heartRate}));
                    }
                }
            }
        };

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback);
    }

    public void stopHeartRateMonitoring() {
        if (measureClient != null && measureCallback != null) {
            Log.d(TAG, "Stopping HR monitoring");
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback);
        }
    }
}
