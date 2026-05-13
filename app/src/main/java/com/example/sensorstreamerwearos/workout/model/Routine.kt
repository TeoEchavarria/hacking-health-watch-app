package com.example.sensorstreamerwearos.workout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Workout routine model shared between phone and watch apps.
 * 
 * Deserialized from JSON received via Wearable Data Layer MessageClient.
 * Path: "/workout/push"
 */
@Serializable
data class Routine(
    @SerialName("routineId")
    val routineId: String,
    
    @SerialName("startAt")
    val startAt: String, // ISO 8601 timestamp
    
    @SerialName("segments")
    val segments: List<Segment>
)

/**
 * Individual workout segment (WORK or REST).
 */
@Serializable
data class Segment(
    @SerialName("type")
    val type: SegmentType,
    
    @SerialName("label")
    val label: String,
    
    @SerialName("durationSec")
    val durationSec: Int
)

@Serializable
enum class SegmentType {
    @SerialName("WORK")
    WORK,
    
    @SerialName("REST")
    REST
}
