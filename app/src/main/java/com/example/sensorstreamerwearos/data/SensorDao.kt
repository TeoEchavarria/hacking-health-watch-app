package com.example.sensorstreamerwearos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    @Insert
    suspend fun insert(entity: SensorBufferEntity)

    @Query("SELECT * FROM sensor_buffer WHERE seq > :lastAckSeq ORDER BY seq ASC LIMIT 100")
    suspend fun getPendingBatches(lastAckSeq: Long): List<SensorBufferEntity>

    @Query("SELECT MAX(seq) FROM sensor_buffer")
    fun getMaxSeqFlow(): Flow<Long?>

    @Query("SELECT MAX(seq) FROM sensor_buffer")
    suspend fun getMaxSeq(): Long?

    @Query("DELETE FROM sensor_buffer WHERE seq <= :ackSeq")
    suspend fun deleteAcknowledged(ackSeq: Long)

    @Query("SELECT COUNT(*) FROM sensor_buffer")
    fun getBacklogCount(): Flow<Int>
}
