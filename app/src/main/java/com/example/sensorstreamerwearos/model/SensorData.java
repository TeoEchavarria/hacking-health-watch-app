package com.example.sensorstreamerwearos.model;

public class SensorData {
    public long timestamp;
    public String type;
    public float[] values;

    public SensorData(long timestamp, String type, float[] values) {
        this.timestamp = timestamp;
        this.type = type;
        this.values = values;
    }
}
