package com.aegisvision.medbud.decision

import com.aegisvision.medbud.perception.nowSec

/**
 * Top-level triage categories surfaced by [TriageEngine].
 *
 * Kept deliberately small — one concept per category. [AIRWAY_RISK] exists
 * separately from [BREATHING_RISK] because downstream Phase 2.2 logic will
 * act on them differently (abnormal breathing ≠ no breathing).
 */
enum class PriorityType {
    SCENE_SAFETY,
    MAJOR_BLEEDING,
    AIRWAY_RISK,
    BREATHING_RISK,
    UNRESPONSIVE_PERSON,
    UNKNOWN_MEDICAL_RISK,
    NO_PERSON_DETECTED,
    MONITOR_ONLY,
}

enum class UrgencyLevel { LOW, MODERATE, HIGH, CRITICAL }

/**
 * Structured triage decision emitted once per [PerceptionState] update.
 *
 *  * [primaryPriority] — what the engine thinks matters most right now.
 *  * [secondaryPriorities] — other active categories, sorted by score.
 *  * [urgency] — LOW..CRITICAL; derived from primary + trend + confidence.
 *  * [confidence] — decision confidence, NOT equal to perception confidence.
 *  * [blockers] — reasons the engine could not commit more strongly.
 *  * [missingInfo] — observational gaps to close next.
 *  * [nextFocus] — short machine-usable focus tag (e.g. "locate_wound").
 *  * [rationale] — one-sentence factual explanation.
 */
data class DecisionState(
    val timestampSec: Double,
    val primaryPriority: PriorityType,
    val secondaryPriorities: List<PriorityType>,
    val urgency: UrgencyLevel,
    val confidence: Double,
    val blockers: List<String>,
    val missingInfo: List<String>,
    val nextFocus: String,
    val rationale: String,
) {
    companion object {
        fun initial() = DecisionState(
            timestampSec = nowSec(),
            primaryPriority = PriorityType.MONITOR_ONLY,
            secondaryPriorities = emptyList(),
            urgency = UrgencyLevel.LOW,
            confidence = 0.0,
            blockers = listOf("no observations yet"),
            missingInfo = listOf("scene", "patient"),
            nextFocus = "continue_monitoring",
            rationale = "Awaiting first frame.",
        )
    }
}

/**
 * Engine-internal assessment for a single candidate priority. Not exported
 * in [DecisionState]; kept here because it documents the scoring contract.
 */
internal data class PriorityAssessment(
    val type: PriorityType,
    val score: Double,
    val confidence: Double,
    val blockers: List<String>,
    val missingInfo: List<String>,
    val nextFocus: String?,
    val reason: String,
) {
    companion object {
        internal fun inactive(
            type: PriorityType,
            reason: String,
            blockers: List<String> = emptyList(),
        ) = PriorityAssessment(
            type = type,
            score = 0.0,
            confidence = 0.0,
            blockers = blockers,
            missingInfo = emptyList(),
            nextFocus = null,
            reason = reason,
        )
    }
}
