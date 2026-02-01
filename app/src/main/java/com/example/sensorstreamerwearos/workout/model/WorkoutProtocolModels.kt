package com.example.sensorstreamerwearos.workout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Routine payload sent from phone to watch via /workout/start.
 * Shared schema between phone and watch apps (use kotlinx serialization).
 */
@Serializable
data class RoutinePayload(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("routineId") val routineId: String,
    @SerialName("routineName") val routineName: String,
    @SerialName("blocks") val blocks: List<RoutineBlockPayload>,
    @SerialName("sentAt") val sentAt: String
)

@Serializable
data class RoutineBlockPayload(
    @SerialName("blockId") val blockId: String,
    @SerialName("exerciseName") val exerciseName: String,
    @SerialName("sets") val sets: Int,
    @SerialName("targetWeight") val targetWeight: Float,
    @SerialName("targetReps") val targetReps: Int? = null,
    @SerialName("restSec") val restSec: Int
)

/**
 * ACK sent from watch to phone via /workout/ack.
 */
@Serializable
data class WorkoutAckPayload(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("routineId") val routineId: String,
    @SerialName("status") val status: String, // "STARTED" | "REJECTED"
    @SerialName("reason") val reason: String? = null,
    @SerialName("at") val at: String
)

/**
 * Optional event sent from watch to phone via /workout/event.
 */
@Serializable
data class WorkoutEventPayload(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("type") val type: String, // "DONE_SET" | "SKIP_REST" | "STOP" | "FINISH"
    @SerialName("blockId") val blockId: String,
    @SerialName("setIndex") val setIndex: Int,
    @SerialName("at") val at: String
)
