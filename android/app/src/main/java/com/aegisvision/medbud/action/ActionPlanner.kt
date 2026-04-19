package com.aegisvision.medbud.action

import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.clarification.ClarificationType
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.PriorityType
import com.aegisvision.medbud.perception.PerceptionState
import com.aegisvision.medbud.perception.Source
import com.aegisvision.medbud.perception.nowSec

/**
 * Phase 2.3 — action planning engine.
 *
 * Pure function from `(PerceptionState, DecisionState, ClarificationState)`
 * → [ActionPlanState]. Decides:
 *
 *   1. readiness (gating)
 *   2. primary + secondary action branches
 *   3. ordered internal step template
 *   4. explicit safety flags
 *
 * Does NOT produce conversational scripts. `plannedSteps` carry
 * stable snake_case `instructionKey`s — Phase 3 lifts those into
 * spoken guidance later.
 */
object ActionPlanner {

    // ---------------------------------------------------- priority → action

    private val PRIORITY_TO_ACTION: Map<PriorityType, ActionType> = mapOf(
        PriorityType.SCENE_SAFETY        to ActionType.ENSURE_SCENE_SAFETY,
        PriorityType.MAJOR_BLEEDING      to ActionType.CONTROL_BLEEDING,
        PriorityType.BREATHING_RISK      to ActionType.ASSESS_BREATHING,
        PriorityType.AIRWAY_RISK         to ActionType.PROTECT_AIRWAY,
        PriorityType.UNRESPONSIVE_PERSON to ActionType.ASSESS_RESPONSIVENESS,
        PriorityType.MONITOR_ONLY        to ActionType.MONITOR_PATIENT,
        PriorityType.NO_PERSON_DETECTED  to ActionType.LOCATE_PATIENT,
        PriorityType.UNKNOWN_MEDICAL_RISK to ActionType.WAIT_FOR_MORE_INFO,
    )

    // ------------------------------------------------------- step templates
    //
    // Internal step templates ONLY. Each `instructionKey` is the contract
    // between Phase 2.3 and Phase 3's delivery layer.

    private val STEP_TEMPLATES: Map<ActionType, List<ActionStep>> = mapOf(
        ActionType.ENSURE_SCENE_SAFETY to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Assess scene",
                "assess_scene", requiresConfirmation = false, safetyCritical = true),
            ActionStep(2, ActionStepType.OBSERVE, "Identify hazard location",
                "identify_hazard", requiresConfirmation = false, safetyCritical = true),
            ActionStep(3, ActionStepType.INTERVENE, "Move to safer position",
                "move_to_safety", requiresConfirmation = true, safetyCritical = true),
            ActionStep(4, ActionStepType.CONFIRM, "Confirm safe position",
                "confirm_safe_position", requiresConfirmation = true, safetyCritical = true),
        ),
        ActionType.CONTROL_BLEEDING to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Locate bleeding source",
                "locate_bleeding_source", requiresConfirmation = false, safetyCritical = true),
            ActionStep(2, ActionStepType.INTERVENE, "Apply bleeding control",
                "apply_bleeding_control", requiresConfirmation = true, safetyCritical = true),
            ActionStep(3, ActionStepType.REASSESS, "Reassess bleeding",
                "reassess_bleeding", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.ASSESS_BREATHING to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Locate chest",
                "locate_chest", requiresConfirmation = false, safetyCritical = false),
            ActionStep(2, ActionStepType.OBSERVE, "Confirm chest motion",
                "confirm_chest_motion", requiresConfirmation = false, safetyCritical = true),
            ActionStep(3, ActionStepType.REASSESS, "Reassess breathing",
                "reassess_breathing", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.PROTECT_AIRWAY to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Observe airway",
                "observe_airway", requiresConfirmation = false, safetyCritical = true),
            ActionStep(2, ActionStepType.INTERVENE, "Clear airway if safe",
                "clear_airway_if_safe", requiresConfirmation = true, safetyCritical = true),
            ActionStep(3, ActionStepType.REASSESS, "Reassess airway",
                "reassess_airway", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.ASSESS_RESPONSIVENESS to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Observe patient",
                "observe_patient", requiresConfirmation = false, safetyCritical = false),
            ActionStep(2, ActionStepType.CONFIRM, "Attempt verbal response",
                "attempt_verbal_response", requiresConfirmation = true, safetyCritical = false),
            ActionStep(3, ActionStepType.REASSESS, "Reassess responsiveness",
                "reassess_responsiveness", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.MONITOR_PATIENT to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "General observation",
                "general_observation", requiresConfirmation = false, safetyCritical = false),
            ActionStep(2, ActionStepType.REASSESS, "Reassess state",
                "reassess_state", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.LOCATE_PATIENT to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Scan surroundings",
                "scan_surroundings", requiresConfirmation = false, safetyCritical = false),
            ActionStep(2, ActionStepType.CONFIRM, "Confirm patient visible",
                "confirm_patient_visible", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.WAIT_FOR_MORE_INFO to listOf(
            ActionStep(1, ActionStepType.OBSERVE, "Continue observation",
                "continue_observation", requiresConfirmation = false, safetyCritical = false),
            ActionStep(2, ActionStepType.WAIT, "Gather more frames",
                "gather_more_frames", requiresConfirmation = false, safetyCritical = false),
        ),
        ActionType.HANDOFF_MODE to listOf(
            ActionStep(1, ActionStepType.DELEGATE, "Handoff to external",
                "handoff_to_external", requiresConfirmation = true, safetyCritical = true),
        ),
    )

    /**
     * Clarifications that genuinely require a pause — not just the next
     * observation step. Camera-reposition clarifications like
     * `LOCATE_WOUND` / `LOCATE_CHEST` are naturally the first step of the
     * corresponding action plan, so they don't block readiness here.
     */
    private val BLOCKING_CLARIFICATIONS = setOf(
        ClarificationType.RESOLVE_SIGNAL_CONTRADICTION,
        ClarificationType.CONFIRM_BREATHING,
        ClarificationType.CONFIRM_RESPONSIVENESS,
    )

    // --------------------------------------------------------------- evaluate

    fun evaluate(
        perception: PerceptionState,
        decision: DecisionState,
        clarification: ClarificationState,
    ): ActionPlanState {
        val now = nowSec()

        val intendedPrimary = PRIORITY_TO_ACTION[decision.primaryPriority]
            ?: ActionType.MONITOR_PATIENT

        val safetyFlags = computeSafetyFlags(perception, decision, clarification)
        val (status, blockers, readiness) =
            computeReadiness(decision, clarification, safetyFlags)

        // When the planner isn't ready, switch the outward action to a
        // holding pattern — except for NO_PERSON_DETECTED, which already
        // has LOCATE_PATIENT as a legitimate active path.
        val finalPrimary = when {
            status == ActionPlanStatus.NOT_READY &&
                decision.primaryPriority != PriorityType.NO_PERSON_DETECTED ->
                    ActionType.WAIT_FOR_MORE_INFO
            else -> intendedPrimary
        }

        val secondaries = decision.secondaryPriorities
            .mapNotNull { PRIORITY_TO_ACTION[it] }
            .filter { it != finalPrimary }
            .distinct()

        val plannedSteps = STEP_TEMPLATES[finalPrimary].orEmpty()

        val rationale = buildRationale(decision, clarification, finalPrimary, status, safetyFlags)

        return ActionPlanState(
            timestampSec = now,
            status = status,
            primaryAction = finalPrimary,
            secondaryActions = secondaries,
            readiness = readiness,
            blockers = blockers,
            safetyFlags = safetyFlags,
            plannedSteps = plannedSteps,
            rationale = rationale,
        )
    }

    // --------------------------------------------------------- readiness gating

    private fun computeReadiness(
        d: DecisionState,
        c: ClarificationState,
        safetyFlags: List<String>,
    ): Triple<ActionPlanStatus, List<String>, Double> {
        val blockers = mutableListOf<String>()
        val clarType = c.primaryClarificationNeed.type

        // 1. Explicit passive mode: nothing acute → MONITOR_ONLY.
        if (d.primaryPriority == PriorityType.MONITOR_ONLY) {
            return Triple(
                ActionPlanStatus.MONITOR_ONLY,
                emptyList(),
                d.confidence.coerceAtMost(0.95),
            )
        }

        // 2. No person: already a valid action branch (LOCATE_PATIENT); gate
        //    with caution since we haven't seen a patient at all.
        if (d.primaryPriority == PriorityType.NO_PERSON_DETECTED) {
            return Triple(
                ActionPlanStatus.READY_WITH_CAUTION,
                emptyList(),
                d.confidence.coerceAtMost(0.90),
            )
        }

        // 3. Evidence too thin for a specific branch.
        if (d.primaryPriority == PriorityType.UNKNOWN_MEDICAL_RISK) {
            blockers += "evidence too thin for a specific action"
            return Triple(ActionPlanStatus.NOT_READY, blockers, d.confidence)
        }

        // 4. Clarification that genuinely requires a pause.
        if (clarType in BLOCKING_CLARIFICATIONS) {
            blockers += "clarification required: ${clarType.name.lowercase()}"
            val readiness = (d.confidence * 0.6).coerceAtMost(0.8)
            return Triple(ActionPlanStatus.CLARIFY_FIRST, blockers, readiness)
        }

        // 5. Decision confidence too low to act.
        //    Relaxed to 0.15 so speech-only critical signals ("he's not breathing")
        //    still produce an actionable plan. Adaptation engine will push the
        //    delivery into SIMPLIFIED mode so the app stays cautious in words.
        if (d.confidence < 0.15) {
            blockers += "decision confidence ${"%.2f".format(d.confidence)} below action threshold"
            return Triple(ActionPlanStatus.NOT_READY, blockers, d.confidence)
        }

        // 6. Safety flags → ready but cautious.
        val status = if (safetyFlags.isNotEmpty())
            ActionPlanStatus.READY_WITH_CAUTION
        else
            ActionPlanStatus.READY

        val penalty = if (safetyFlags.isNotEmpty()) 0.15 else 0.0
        val readiness = (d.confidence - penalty).coerceIn(0.0, 0.95)
        return Triple(status, blockers, readiness)
    }

    // --------------------------------------------------------- safety flags

    private fun computeSafetyFlags(
        p: PerceptionState,
        d: DecisionState,
        c: ClarificationState,
    ): List<String> {
        val flags = mutableListOf<String>()

        val dangerousScene = setOf("fire", "electrical", "weapon")
        val activeHazards = p.sceneRisk.filter { it in dangerousScene }
        if (activeHazards.isNotEmpty()) {
            flags += "scene hazard present: ${activeHazards.joinToString(", ")}"
        }

        if (p.personVisible.value == "no") {
            flags += "no patient visible"
        }

        val speechOnlyBreathing = p.breathing.source == Source.SPEECH &&
            p.breathing.visualSupport < 0.30 &&
            p.breathing.value in setOf("none", "abnormal")
        if (speechOnlyBreathing) {
            flags += "breathing concern lacks visual confirmation"
        }

        val speechOnlyConscious = p.conscious.source == Source.SPEECH &&
            p.conscious.visualSupport < 0.30 &&
            p.conscious.value == "no"
        if (speechOnlyConscious) {
            flags += "unresponsiveness lacks visual confirmation"
        }

        if (c.primaryClarificationNeed.type == ClarificationType.RESOLVE_SIGNAL_CONTRADICTION) {
            flags += "unresolved signal contradiction"
        }

        // Low confidence on the field that drives the primary priority.
        val primaryFieldConf: Double? = when (d.primaryPriority) {
            PriorityType.MAJOR_BLEEDING -> p.bleeding.confidence
            PriorityType.BREATHING_RISK, PriorityType.AIRWAY_RISK -> p.breathing.confidence
            PriorityType.UNRESPONSIVE_PERSON -> p.conscious.confidence
            else -> null
        }
        if (primaryFieldConf != null && primaryFieldConf < 0.30) {
            flags += "low confidence in primary field"
        }

        return flags
    }

    // --------------------------------------------------------- rationale

    private fun buildRationale(
        d: DecisionState,
        c: ClarificationState,
        primaryAction: ActionType,
        status: ActionPlanStatus,
        safetyFlags: List<String>,
    ): String {
        val clarName = c.primaryClarificationNeed.type.name.lowercase()
        val actionName = primaryAction.name.lowercase()
        val priorityName = d.primaryPriority.name.lowercase()

        return when (status) {
            ActionPlanStatus.MONITOR_ONLY ->
                "No acute signals; keeping patient under observation."
            ActionPlanStatus.CLARIFY_FIRST ->
                "Intended action '$actionName' held — clarification '$clarName' required first."
            ActionPlanStatus.NOT_READY ->
                "Evidence insufficient for a specific action " +
                    "(decision confidence=${"%.2f".format(d.confidence)})."
            ActionPlanStatus.READY_WITH_CAUTION ->
                "Readying '$actionName' from primary priority '$priorityName' " +
                    "with cautions: ${safetyFlags.joinToString("; ")}."
            ActionPlanStatus.READY ->
                "Readying '$actionName' from primary priority '$priorityName'."
        }
    }
}
