package com.example.sensorstreamerwearos.network

import com.example.sensorstreamerwearos.data.SensorBufferEntity
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

class ChannelStreamSender(outputStream: OutputStream) {
    private val dos = DataOutputStream(BufferedOutputStream(outputStream))

    fun sendFrame(entity: SensorBufferEntity) {
        synchronized(dos) {
            // Header
            dos.writeByte(Protocol.VERSION) // Protocol Version
            dos.writeLong(entity.seq)      // Sequence Number
            dos.writeLong(entity.timestamp)// Timestamp
            dos.writeUTF(entity.type)      // Sensor Type
            
            // Payload
            dos.writeInt(entity.payload.size)
            dos.write(entity.payload)
            
            dos.flush()
        }
    }

    fun close() {
        try {
            dos.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
