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
 * Event sent between phone and watch via /workout/event.
 */
@Serializable
data class WorkoutEventPayload(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("type") val type: String, // "DONE_SET" | "UNDO_SET" | "FINISH_WORKOUT"
    @SerialName("blockId") val blockId: String?,
    @SerialName("setIndex") val setIndex: Int?,
    @SerialName("source") val source: String, // "PHONE" | "WATCH"
    @SerialName("at") val at: String
)

@Serializable
data class WorkoutStateRequestPayload(
    @SerialName("sessionId") val sessionId: String = ""
)

/**
 * State snapshot sent from watch to phone via /workout/state.
 * Phone mirrors this state; watch is source of truth.
 */
@Serializable
data class WorkoutStatePayload(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("routineId") val routineId: String,
    @SerialName("exerciseName") val exerciseName: String,
    @SerialName("currentSet") val currentSet: Int, // 1-based
    @SerialName("totalSets") val totalSets: Int,
    @SerialName("mode") val mode: String, // "WORK", "REST", "FINISHED", "IDLE"
    @SerialName("progress") val progress: Float,
    @SerialName("updatedAt") val updatedAt: String
)
