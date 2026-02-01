package com.example.sensorstreamerwearos.workout.model

import com.google.gson.annotations.SerializedName

/**
 * Workout routine model shared between phone and watch apps.
 * 
 * Deserialized from JSON received via Wearable Data Layer MessageClient.
 * Path: "/workout/push"
 */
data class Routine(
    @SerializedName("routineId")
    val routineId: String,
    
    @SerializedName("startAt")
    val startAt: String, // ISO 8601 timestamp
    
    @SerializedName("segments")
    val segments: List<Segment>
)

/**
 * Individual workout segment (WORK or REST).
 */
data class Segment(
    @SerializedName("type")
    val type: SegmentType,
    
    @SerializedName("label")
    val label: String,
    
    @SerializedName("durationSec")
    val durationSec: Int
)

enum class SegmentType {
    @SerializedName("WORK")
    WORK,
    
    @SerializedName("REST")
    REST
}
