package com.example.sensorstreamerwearos.workout.repository

import com.example.sensorstreamerwearos.workout.model.RoutineBlockPayload
import com.example.sensorstreamerwearos.workout.model.RoutinePayload

/**
 * Provides RoutinePayload by routineId (Protocol v2).
 * Definitions mirror phone Daily Training blocks A, B, C, D.
 */
object RoutineRepository {

    /**
     * Load routine by ID. Returns null if not found.
     * Caller supplies sessionId and sentAt from WorkoutStartMessage.
     */
    fun getRoutine(routineId: String, sessionId: String, routineName: String, sentAt: String): RoutinePayload? {
        val blocks = getBlocksForRoutineId(routineId) ?: return null
        return RoutinePayload(
            sessionId = sessionId,
            routineId = routineId,
            routineName = routineName,
            blocks = blocks,
            sentAt = sentAt
        )
    }

    /**
     * Returns true if blockId exists within the routine (for optional validation).
     */
    fun hasBlock(routineId: String, blockId: String): Boolean {
        val blocks = getBlocksForRoutineId(routineId) ?: return false
        return blocks.any { it.blockId == blockId }
    }

    private fun getBlocksForRoutineId(routineId: String): List<RoutineBlockPayload>? = when (routineId) {
        "daily_block_a_cycling" -> blockACycling
        "daily_block_a_running" -> blockARunning
        "daily_block_a_swimming" -> blockASwimming
        "daily_block_b_30" -> blockB(30)
        "daily_block_b_45" -> blockB(45)
        "daily_block_c" -> blockC
        "daily_block_d" -> blockD
        else -> null
    }

    private val blockACycling = listOf(
        RoutineBlockPayload("seg_0", "Easy pace", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0),
        RoutineBlockPayload("seg_1", "Moderate pace", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0),
        RoutineBlockPayload("seg_2", "Easy pace", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0)
    )
    private val blockARunning = listOf(
        RoutineBlockPayload("seg_0", "Brisk walk", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0),
        RoutineBlockPayload("seg_1", "Easy continuous run", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0),
        RoutineBlockPayload("seg_2", "Walking", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0)
    )
    private val blockASwimming = listOf(
        RoutineBlockPayload("seg_0", "Kick with board", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0),
        RoutineBlockPayload("seg_1", "Backstroke (easy)", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0),
        RoutineBlockPayload("seg_2", "Relaxed freestyle", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0)
    )

    /** Block B: 6 exercises, 3 sets each with rest between sets (Exercise - Rest - Next Set). */
    private fun blockB(restSec: Int): List<RoutineBlockPayload> = listOf(
        RoutineBlockPayload("squats", "Squats", sets = 3, targetWeight = 0f, targetReps = 15, restSec = restSec),
        RoutineBlockPayload("lunges", "Alternating lunges", sets = 3, targetWeight = 0f, targetReps = 10, restSec = restSec),
        RoutineBlockPayload("glute_bridges", "Glute bridges", sets = 3, targetWeight = 0f, targetReps = 20, restSec = restSec),
        RoutineBlockPayload("calf_raises", "Calf raises", sets = 3, targetWeight = 0f, targetReps = 20, restSec = restSec),
        RoutineBlockPayload("hollow_hold", "Hollow body hold", sets = 3, targetWeight = 0f, targetReps = null, restSec = restSec),
        RoutineBlockPayload("superman", "Superman hold", sets = 3, targetWeight = 0f, targetReps = null, restSec = restSec)
    )

    /** Block C: 6 exercises, 3 sets each with 30s rest between sets. */
    private val blockC: List<RoutineBlockPayload> = listOf(
        RoutineBlockPayload("step_ups", "Step-ups", sets = 3, targetWeight = 0f, targetReps = 10, restSec = 30),
        RoutineBlockPayload("pistol_squats", "Assisted pistol squats", sets = 3, targetWeight = 0f, targetReps = 5, restSec = 30),
        RoutineBlockPayload("wall_sit", "Wall sit", sets = 3, targetWeight = 0f, targetReps = null, restSec = 30),
        RoutineBlockPayload("bicycle_crunches", "Bicycle crunches", sets = 3, targetWeight = 0f, targetReps = 20, restSec = 30),
        RoutineBlockPayload("forearm_plank", "Forearm plank", sets = 3, targetWeight = 0f, targetReps = null, restSec = 30),
        RoutineBlockPayload("lateral_leg_raises", "Lateral leg raises", sets = 3, targetWeight = 0f, targetReps = 15, restSec = 30)
    )

    private val blockD: List<RoutineBlockPayload> = buildList {
        add(RoutineBlockPayload("hip_mobility", "Hip mobility drills", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0))
        add(RoutineBlockPayload("ankle_mobility", "Ankle mobility drills", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0))
        add(RoutineBlockPayload("spine_mobility", "Spine mobility (cat-cow)", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0))
        add(RoutineBlockPayload("hand_open_close", "Hand open/close", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0))
        add(RoutineBlockPayload("forearm_pronation", "Forearm pronation/supination", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0))
        add(RoutineBlockPayload("wrist_flexion", "Gentle wrist flexion/extension", sets = 1, targetWeight = 0f, targetReps = null, restSec = 0))
    }
}
