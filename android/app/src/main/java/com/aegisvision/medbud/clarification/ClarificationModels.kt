package com.aegisvision.medbud.clarification

import com.aegisvision.medbud.decision.PriorityType
import com.aegisvision.medbud.perception.nowSec

/**
 * What kind of clarification the system is asking for right now.
 *
 * Categories are narrow and machine-usable — they map 1:1 to a
 * [PromptInstruction.shortLabel]. Downstream agents (Phase 3) consume
 * the type to know *what* to do next, not *how to do it*.
 */
enum class ClarificationType {
    LOCATE_PATIENT,
    ASSESS_SCENE_SAFETY,
    LOCATE_WOUND,
    LOCATE_CHEST,
    CONFIRM_BREATHING,
    CONFIRM_RESPONSIVENESS,
    CONFIRM_BLEEDING_SOURCE,
    RESOLVE_SIGNAL_CONTRADICTION,
    GATHER_GENERAL_INFORMATION,
    NO_CLARIFICATION_NEEDED,
}

/** How the clarification should be surfaced. */
enum class PromptMode {
    /** Ask the wearer to reposition the camera (glasses). */
    CAMERA_MOVE,
    /** Ask the wearer a short verbal question. */
    ASK_USER,
    /** Nothing to do; continue passive monitoring. */
    NONE,
}

/**
 * A short, app-usable instruction for the next clarification step.
 *
 *  * [mode]        — how to deliver it (camera guidance vs. question).
 *  * [target]      — semantic target ("chest", "wound", "scene", …).
 *  * [promptText]  — exact short sentence to surface to the wearer.
 *  * [shortLabel]  — snake_case machine tag; stable, matches Phase 2.1 nextFocus.
 */
data class PromptInstruction(
    val mode: PromptMode,
    val target: String,
    val promptText: String,
    val shortLabel: String,
) {
    companion object {
        fun passive() = PromptInstruction(
            mode = PromptMode.NONE,
            target = "",
            promptText = "",
            shortLabel = "continue_monitoring",
        )
    }
}

/**
 * One candidate clarification need, scored against others in [ClarificationState].
 *
 *  * [priority]         — 0.0–1.0; higher wins the primary slot.
 *  * [blockingPriority] — which Phase 2.1 priority this would unblock.
 *  * [targetField]      — which [PerceptionState] field is being clarified.
 */
data class ClarificationNeed(
    val type: ClarificationType,
    val priority: Double,
    val reason: String,
    val blockingPriority: PriorityType?,
    val targetField: String?,
)

/**
 * Final output of Phase 2.2. Emitted via StateFlow alongside
 * PerceptionState and DecisionState.
 *
 *  * [primaryClarificationNeed] — the single best next clarification.
 *  * [candidateNeeds]           — other active needs, priority-sorted.
 *  * [recommendedPrompt]        — concrete app-usable request.
 *  * [confidenceGainEstimate]   — 0.0–1.0; how much answering would help.
 *  * [rationale]                — factual one-sentence explanation.
 */
data class ClarificationState(
    val timestampSec: Double,
    val primaryClarificationNeed: ClarificationNeed,
    val candidateNeeds: List<ClarificationNeed>,
    val recommendedPrompt: PromptInstruction,
    val confidenceGainEstimate: Double,
    val rationale: String,
) {
    companion object {
        fun initial() = ClarificationState(
            timestampSec = nowSec(),
            primaryClarificationNeed = ClarificationNeed(
                type = ClarificationType.NO_CLARIFICATION_NEEDED,
                priority = 0.0,
                reason = "no observations yet",
                blockingPriority = null,
                targetField = null,
            ),
            candidateNeeds = emptyList(),
            recommendedPrompt = PromptInstruction.passive(),
            confidenceGainEstimate = 0.0,
            rationale = "Awaiting first frame.",
        )
    }
}
