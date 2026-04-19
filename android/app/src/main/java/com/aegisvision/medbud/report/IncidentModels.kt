package com.aegisvision.medbud.report

import com.aegisvision.medbud.perception.nowSec

/**
 * Phase 6 — data classes for the incident recording + handoff report system.
 *
 * This layer adds no medical reasoning. It records what Phases 1–5
 * already decided, structures a timeline, and generates a readable
 * summary for the next trained person.
 */

/** Lifecycle of a single incident session. */
enum class IncidentStatus {
    ACTIVE,        // session currently recording
    ENDED,         // user terminated (Stop Stream, etc.)
    HANDED_OFF,    // Phase 5 triggered a handoff — report generated
    ABORTED,       // process died / cancelled before a proper end
}

/**
 * Kinds of events the logger tracks. Keep the set narrow — this is the
 * vocabulary of the handoff, not a debug log.
 */
enum class TimelineEventType {
    SESSION_START,
    SESSION_END,
    PATIENT_DETECTED,
    PATIENT_LOST,
    BLEEDING_OBSERVED,
    BLEEDING_RESOLVED,
    BREATHING_CONCERN,
    BREATHING_NORMAL,
    CONSCIOUSNESS_CONCERN,
    CONSCIOUSNESS_NORMAL,
    SCENE_RISK,
    PRIORITY_CHANGE,
    ACTION_CHANGE,
    STEP_COMPLETED,
    USER_REPORT,
    CLARIFICATION_REQUESTED,
    ESCALATION,
    HANDOFF_TRIGGERED,
    NOTE,
}

/**
 * Provenance for every timeline entry. This is the honesty layer —
 * the generator uses it to phrase findings as "observed" vs "reported"
 * vs "inferred" rather than flatly asserting medical facts.
 */
enum class EventSource {
    SYSTEM_OBSERVED,   // vision signal (VLM saw it)
    USER_REPORTED,     // from user speech
    SYSTEM_INFERRED,   // triage / planner derivation
    ACTION_TAKEN,      // user or guidance loop completed a step
}

/** One structured entry on the incident timeline. */
data class TimelineEvent(
    val timestampSec: Double,
    val type: TimelineEventType,
    val title: String,
    val details: String,
    val source: EventSource,
)

/** A complete incident session — timeline + (when ended) final report. */
data class IncidentSession(
    val incidentId: String,
    val startTimeSec: Double,
    val endTimeSec: Double?,
    val status: IncidentStatus,
    val timelineEvents: List<TimelineEvent>,
    val finalReport: HandoffReport?,
) {
    companion object {
        fun newActive(): IncidentSession = IncidentSession(
            incidentId = newIncidentId(),
            startTimeSec = nowSec(),
            endTimeSec = null,
            status = IncidentStatus.ACTIVE,
            timelineEvents = emptyList(),
            finalReport = null,
        )

        private fun newIncidentId(): String {
            val t = System.currentTimeMillis()
            return "INC-" + t.toString(36).uppercase().takeLast(8)
        }
    }
}

/**
 * The structured handoff brief. Designed for quick reading under stress
 * — short lists, source-attributed phrases, no medical recommendations.
 */
data class HandoffReport(
    val incidentId: String,
    val startTimeSec: Double,
    val endTimeSec: Double,
    val sceneSummary: String,
    val patientSummary: String,
    val primaryConcerns: List<String>,
    val actionsPerformed: List<String>,
    val timelineSummary: List<String>,
    val unresolvedConcerns: List<String>,
    val confidenceNotes: List<String>,
    val briefText: String,
) {
    /** Wall-clock duration in whole seconds. */
    val durationSec: Int get() = ((endTimeSec - startTimeSec).coerceAtLeast(0.0)).toInt()
}
