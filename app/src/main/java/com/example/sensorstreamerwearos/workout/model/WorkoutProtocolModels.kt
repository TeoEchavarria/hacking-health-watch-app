package com.example.sensorstreamerwearos.workout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol v2: lightweight messages independent from UI models.
 * Paths: phone -> watch "/workout/start", watch -> phone "/workout/ack".
 */
object WorkoutProtocol {
    const val PATH_START = "/workout/start"
    const val PATH_ACK = "/workout/ack"
    const val PROTOCOL_VERSION = 2
    const val TYPE_WORKOUT_START = "WORKOUT_START"
    const val TYPE_WORKOUT_ACK = "WORKOUT_ACK"

    object ReasonCode {
        const val NO_NODES = "NO_NODES"
        const val SEND_FAILED = "SEND_FAILED"
        const val ACK_TIMEOUT = "ACK_TIMEOUT"
        const val INVALID_JSON = "INVALID_JSON"
        const val INVALID_SCHEMA = "INVALID_SCHEMA"
        const val ROUTINE_NOT_FOUND = "ROUTINE_NOT_FOUND"
        const val BLOCK_NOT_FOUND = "BLOCK_NOT_FOUND"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}

@Serializable
data class WorkoutStartMessage(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("type") val type: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("attemptId") val attemptId: String,
    @SerialName("routineId") val routineId: String,
    @SerialName("routineName") val routineName: String,
    @SerialName("blockId") val blockId: String? = null,
    @SerialName("sentAt") val sentAt: Long
)

@Serializable
data class WorkoutAckMessage(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("type") val type: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("attemptId") val attemptId: String,
    @SerialName("success") val success: Boolean,
    @SerialName("reasonCode") val reasonCode: String? = null,
    @SerialName("reasonMessage") val reasonMessage: String? = null,
    @SerialName("sentAt") val sentAt: Long
)

/**
 * Routine payload (internal watch model). Loaded by RoutineRepository from routineId.
 * Sent to WorkoutService after V2 decode + repository lookup.
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
    @SerialName("type") val type: String, // "DONE_SET" | "UNDO_SET" | "FINISH_WORKOUT" | "REP_UPDATE"
    @SerialName("blockId") val blockId: String?,
    @SerialName("setIndex") val setIndex: Int?,
    @SerialName("repCount") val repCount: Int? = null,
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
