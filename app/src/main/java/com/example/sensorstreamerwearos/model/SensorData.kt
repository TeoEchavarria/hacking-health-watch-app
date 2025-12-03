package com.example.sensorstreamerwearos.model

import kotlinx.serialization.Serializable

@Serializable
data class SensorData(
    val deviceId: String,
    val type: String,
    val timestamp: Long,
    val values: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SensorData

        if (deviceId != other.deviceId) return false
        if (type != other.type) return false
        if (timestamp != other.timestamp) return false
        if (!values.contentEquals(other.values)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + values.contentHashCode()
        return result
    }
}
