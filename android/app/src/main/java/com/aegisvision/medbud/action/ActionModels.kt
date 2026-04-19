package com.aegisvision.medbud.action

import com.aegisvision.medbud.perception.nowSec

/**
 * High-level readiness state of the action planner.
 *
 *  * [NOT_READY]          — evidence too weak or contradictions unresolved.
 *  * [CLARIFY_FIRST]      — an open clarification need must be resolved before acting.
 *  * [READY]              — primary action can proceed.
 *  * [READY_WITH_CAUTION] — proceed, but safety flags are surfaced and Phase 3
 *                           should honour them (e.g. slow down, re-confirm).
 *  * [MONITOR_ONLY]       — nothing acute detected; passive observation.
 */
enum class ActionPlanStatus {
    NOT_READY,
    CLARIFY_FIRST,
    READY,
    READY_WITH_CAUTION,
    MONITOR_ONLY,
}

/**
 * Action categories the planner can select. These are abstract branches,
 * NOT conversational scripts. Each maps to an ordered list of
 * [ActionStep]s that Phase 3 will later lift into user-facing delivery.
 */
enum class ActionType {
    ENSURE_SCENE_SAFETY,
    CONTROL_BLEEDING,
    ASSESS_BREATHING,
    PROTECT_AIRWAY,
    ASSESS_RESPONSIVENESS,
    MONITOR_PATIENT,
    LOCATE_PATIENT,
    WAIT_FOR_MORE_INFO,
    HANDOFF_MODE,
}

/**
 * Step granularity tag. Phase 3 uses this to pick the right delivery style
 * (observation guidance, short question, physical-step narration, etc.).
 */
enum class ActionStepType {
    OBSERVE,    // look / reposition camera / gather visual data
    CONFIRM,    // verify a state, e.g. ask a short question
    INTERVENE,  // physical step by the wearer; filled in by Phase 3
    REASSESS,   // re-evaluate after the previous step
    WAIT,       // deliberately wait and gather more context
    DELEGATE,   // escalate outside the app (e.g. emergency services handoff)
}

/**
 * Internal step plan entry. Machine-readable only; the `instructionKey`
 * is the stable snake_case tag Phase 3 will dispatch on.
 */
data class ActionStep(
    val order: Int,
    val type: ActionStepType,
    val label: String,
    val instructionKey: String,
    val requiresConfirmation: Boolean = false,
    val safetyCritical: Boolean = false,
)

/**
 * Final Phase 2.3 output. Emitted via StateFlow.
 *
 *  * [primaryAction]       — selected action branch.
 *  * [secondaryActions]    — other branches that may run after the primary.
 *  * [readiness]           — 0.0–1.0; how prepared the planner thinks it is.
 *  * [blockers]            — reasons the plan is held back (if any).
 *  * [safetyFlags]         — explicit safety conditions Phase 3 must honour.
 *  * [plannedSteps]        — ordered internal steps for the primary action.
 *  * [rationale]           — short factual explanation for this decision.
 */
data class ActionPlanState(
    val timestampSec: Double,
    val status: ActionPlanStatus,
    val primaryAction: ActionType,
    val secondaryActions: List<ActionType>,
    val readiness: Double,
    val blockers: List<String>,
    val safetyFlags: List<String>,
    val plannedSteps: List<ActionStep>,
    val rationale: String,
) {
    companion object {
        fun initial() = ActionPlanState(
            timestampSec = nowSec(),
            status = ActionPlanStatus.NOT_READY,
            primaryAction = ActionType.WAIT_FOR_MORE_INFO,
            secondaryActions = emptyList(),
            readiness = 0.0,
            blockers = listOf("awaiting first frame"),
            safetyFlags = emptyList(),
            plannedSteps = emptyList(),
            rationale = "Awaiting first frame.",
        )
    }
}
