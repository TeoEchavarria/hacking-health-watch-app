package com.example.sensorstreamerwearos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_buffer")
data class SensorBufferEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seq: Long,
    val timestamp: Long,
    val type: String,
    val payload: ByteArray, // Serialized JSON or binary
    val sent: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SensorBufferEntity

        if (id != other.id) return false
        if (seq != other.seq) return false
        if (timestamp != other.timestamp) return false
        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false
        if (sent != other.sent) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + sent.hashCode()
        return result
    }
}
