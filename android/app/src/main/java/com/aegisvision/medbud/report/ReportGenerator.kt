package com.aegisvision.medbud.report

import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.action.ActionType
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.PriorityType
import com.aegisvision.medbud.guidance.GuidanceLoopState
import com.aegisvision.medbud.guidance.LoopStatus
import com.aegisvision.medbud.perception.PerceptionState
import com.aegisvision.medbud.perception.Source
import com.aegisvision.medbud.perception.nowSec

/**
 * Converts an [IncidentSession] + the final Phase-1-3 snapshots into a
 * [HandoffReport].
 *
 * Writing rules:
 *   1. Every medical statement is attributed: "observed", "user-reported",
 *      or "inferred". Nothing is asserted as fact.
 *   2. Low-confidence / speech-only findings get flagged in
 *      [HandoffReport.confidenceNotes] and softened in the brief.
 *   3. No diagnosis. No treatment recommendations. No judgement about
 *      what the responder should do next.
 *   4. Lines are short. A tired medic reads this in ten seconds.
 */
object ReportGenerator {

    fun generate(
        session: IncidentSession,
        perception: PerceptionState,
        decision: DecisionState,
        plan: ActionPlanState,
        guidance: GuidanceLoopState,
    ): HandoffReport {
        val end = session.endTimeSec ?: nowSec()

        val sceneSummary = buildSceneSummary(perception, session)
        val patientSummary = buildPatientSummary(perception)
        val primaryConcerns = buildPrimaryConcerns(perception, decision, session)
        val actionsPerformed = buildActionsPerformed(session)
        val timelineSummary = buildTimelineSummary(session)
        val unresolvedConcerns = buildUnresolvedConcerns(perception, plan, guidance)
        val confidenceNotes = buildConfidenceNotes(perception)
        val briefText = buildBriefText(
            scene = sceneSummary,
            patient = patientSummary,
            concerns = primaryConcerns,
            actions = actionsPerformed,
            unresolved = unresolvedConcerns,
            notes = confidenceNotes,
            handedOff = session.status == IncidentStatus.HANDED_OFF ||
                guidance.loopStatus == LoopStatus.HANDOFF,
        )

        return HandoffReport(
            incidentId = session.incidentId,
            startTimeSec = session.startTimeSec,
            endTimeSec = end,
            sceneSummary = sceneSummary,
            patientSummary = patientSummary,
            primaryConcerns = primaryConcerns,
            actionsPerformed = actionsPerformed,
            timelineSummary = timelineSummary,
            unresolvedConcerns = unresolvedConcerns,
            confidenceNotes = confidenceNotes,
            briefText = briefText,
        )
    }

    // ──────────────────────────────────────────────────────── scene

    private fun buildSceneSummary(p: PerceptionState, s: IncidentSession): String {
        val risks = (s.timelineEvents.filter { it.type == TimelineEventType.SCENE_RISK }
            .map { it.title.removePrefix("Scene risk: ") }
            .toSet() + p.sceneRisk.filter { it != "none" }.toSet()).toList()
        return when {
            risks.isEmpty() -> "No scene hazards observed."
            else -> "Scene risks observed: ${risks.joinToString(", ")}."
        }
    }

    // ──────────────────────────────────────────────────────── patient

    private fun buildPatientSummary(p: PerceptionState): String {
        val parts = mutableListOf<String>()
        when (p.personVisible.value) {
            "yes" -> parts += "Patient in view"
            "no"  -> parts += "Patient not currently in view"
        }
        if (p.bodyPartsVisible.isNotEmpty()) {
            parts += "visible: ${p.bodyPartsVisible.joinToString(", ")}"
        }
        return if (parts.isEmpty()) "Patient state unknown." else parts.joinToString("; ") + "."
    }

    // ──────────────────────────────────────────────────────── concerns

    private fun buildPrimaryConcerns(
        p: PerceptionState,
        d: DecisionState,
        s: IncidentSession,
    ): List<String> {
        val out = mutableListOf<String>()

        if (p.bleeding.value in setOf("minor", "heavy")) {
            val severity = p.bleeding.value
            out += "Bleeding ($severity) — ${attribute(p.bleeding.source)}" +
                ", confidence ${fmt(p.bleeding.confidence)}."
        }
        if (p.breathing.value in setOf("abnormal", "none")) {
            val label = if (p.breathing.value == "none") "breathing absent" else "abnormal breathing"
            out += "$label — ${attribute(p.breathing.source)}" +
                ", confidence ${fmt(p.breathing.confidence)}."
        }
        if (p.conscious.value == "no") {
            out += "Unresponsive — ${attribute(p.conscious.source)}" +
                ", confidence ${fmt(p.conscious.confidence)}."
        }

        // Include the triage system's top priority if it adds information
        // beyond what's already above.
        val priorityPhrase = priorityPhrase(d.primaryPriority)
        if (priorityPhrase != null && out.none { it.contains(priorityPhrase, ignoreCase = true) }) {
            out += "$priorityPhrase — inferred, urgency ${d.urgency.name.lowercase()}."
        }
        // Historical concerns from the timeline (e.g. bleeding that
        // resolved earlier) are handled by timelineSummary, not here.
        if (out.isEmpty() && s.timelineEvents.none { it.type in CONCERN_EVENT_TYPES }) {
            out += "No acute medical concerns observed."
        }
        return out
    }

    private fun priorityPhrase(p: PriorityType): String? = when (p) {
        PriorityType.MAJOR_BLEEDING      -> "Major bleeding"
        PriorityType.BREATHING_RISK      -> "Breathing risk"
        PriorityType.AIRWAY_RISK         -> "Airway risk"
        PriorityType.UNRESPONSIVE_PERSON -> "Unresponsive patient"
        PriorityType.SCENE_SAFETY        -> "Scene safety concern"
        PriorityType.UNKNOWN_MEDICAL_RISK,
        PriorityType.MONITOR_ONLY,
        PriorityType.NO_PERSON_DETECTED  -> null
    }

    // ──────────────────────────────────────────────────────── actions

    private fun buildActionsPerformed(s: IncidentSession): List<String> {
        val done = s.timelineEvents.filter { it.type == TimelineEventType.STEP_COMPLETED }
        if (done.isEmpty()) return emptyList()
        return done.map { e ->
            val step = e.title.removePrefix("Step done: ")
            "$step — user-confirmed."
        }.distinct()
    }

    // ──────────────────────────────────────────────────────── timeline

    private fun buildTimelineSummary(s: IncidentSession): List<String> {
        val events = s.timelineEvents.filter { it.type in TIMELINE_SUMMARY_TYPES }
        return events.map { e ->
            val t = formatRelative(e.timestampSec - s.startTimeSec)
            val src = sourceTag(e.source)
            "[$t] $src ${e.title}" +
                if (e.details.isNotBlank() && e.type != TimelineEventType.SESSION_START &&
                    e.type != TimelineEventType.SESSION_END) " — ${e.details}" else ""
        }
    }

    // ──────────────────────────────────────────────────────── unresolved

    private fun buildUnresolvedConcerns(
        p: PerceptionState,
        plan: ActionPlanState,
        g: GuidanceLoopState,
    ): List<String> {
        val out = mutableListOf<String>()

        if (p.bleeding.value in setOf("minor", "heavy")) {
            out += "Bleeding still present at handoff (${p.bleeding.value})."
        }
        if (p.breathing.value == "none") {
            out += "Breathing reported as absent at handoff."
        } else if (p.breathing.value == "abnormal") {
            out += "Abnormal breathing at handoff."
        }
        if (p.conscious.value == "no") {
            out += "Patient unresponsive at handoff."
        }
        // A plan that was in progress but not completed.
        val lastStep = g.currentStepIndex.coerceAtMost(plan.plannedSteps.size - 1)
        if (plan.primaryAction != ActionType.MONITOR_PATIENT &&
            plan.primaryAction != ActionType.LOCATE_PATIENT &&
            plan.plannedSteps.isNotEmpty() &&
            lastStep in plan.plannedSteps.indices &&
            g.completionState != com.aegisvision.medbud.guidance.StepCompletionState.COMPLETED) {
            val key = plan.plannedSteps[lastStep].instructionKey
            out += "Guidance loop was mid-step ($key) when session ended."
        }
        return out
    }

    // ──────────────────────────────────────────────────────── confidence notes

    private fun buildConfidenceNotes(p: PerceptionState): List<String> {
        val out = mutableListOf<String>()
        if (p.breathing.source == Source.SPEECH && p.breathing.visualSupport < 0.30 &&
            p.breathing.value in setOf("abnormal", "none")) {
            out += "Breathing status is user-reported with weak visual confirmation."
        }
        if (p.conscious.source == Source.SPEECH && p.conscious.visualSupport < 0.30 &&
            p.conscious.value == "no") {
            out += "Unresponsiveness is user-reported; camera did not clearly confirm."
        }
        if (p.bleeding.confidence < 0.30 && p.bleeding.value in setOf("minor", "heavy")) {
            out += "Bleeding severity has low confidence."
        }
        if (p.personVisible.value == "no") {
            out += "Patient was not visible at time of report — medical fields inferred from prior frames."
        }
        return out
    }

    // ──────────────────────────────────────────────────────── brief

    private fun buildBriefText(
        scene: String,
        patient: String,
        concerns: List<String>,
        actions: List<String>,
        unresolved: List<String>,
        notes: List<String>,
        handedOff: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append(patient).append(" ").append(scene)
        if (concerns.isNotEmpty()) {
            sb.append(" Primary: ").append(concerns.joinToString(" "))
        }
        if (actions.isNotEmpty()) {
            sb.append(" Actions guided: ").append(actions.joinToString(" "))
        }
        if (unresolved.isNotEmpty()) {
            sb.append(" Unresolved: ").append(unresolved.joinToString(" "))
        }
        if (notes.isNotEmpty()) {
            sb.append(" Notes: ").append(notes.joinToString(" "))
        }
        if (handedOff) {
            sb.append(" Handoff was triggered by the guidance loop.")
        }
        return sb.toString().trim()
    }

    // ──────────────────────────────────────────────────────── helpers

    private val TIMELINE_SUMMARY_TYPES = setOf(
        TimelineEventType.SESSION_START,
        TimelineEventType.PATIENT_DETECTED,
        TimelineEventType.PATIENT_LOST,
        TimelineEventType.BLEEDING_OBSERVED,
        TimelineEventType.BLEEDING_RESOLVED,
        TimelineEventType.BREATHING_CONCERN,
        TimelineEventType.BREATHING_NORMAL,
        TimelineEventType.CONSCIOUSNESS_CONCERN,
        TimelineEventType.CONSCIOUSNESS_NORMAL,
        TimelineEventType.SCENE_RISK,
        TimelineEventType.ACTION_CHANGE,
        TimelineEventType.STEP_COMPLETED,
        TimelineEventType.ESCALATION,
        TimelineEventType.HANDOFF_TRIGGERED,
        TimelineEventType.USER_REPORT,
        TimelineEventType.SESSION_END,
    )

    private val CONCERN_EVENT_TYPES = setOf(
        TimelineEventType.BLEEDING_OBSERVED,
        TimelineEventType.BREATHING_CONCERN,
        TimelineEventType.CONSCIOUSNESS_CONCERN,
        TimelineEventType.SCENE_RISK,
    )

    private fun attribute(s: Source): String = when (s) {
        Source.VISION -> "observed by camera"
        Source.SPEECH -> "reported by user"
        Source.FUSED  -> "fused from camera and speech"
    }

    private fun sourceTag(s: EventSource): String = when (s) {
        EventSource.SYSTEM_OBSERVED -> "[obs]"
        EventSource.USER_REPORTED   -> "[usr]"
        EventSource.SYSTEM_INFERRED -> "[sys]"
        EventSource.ACTION_TAKEN    -> "[act]"
    }

    private fun fmt(d: Double): String = "%.2f".format(d)

    private fun formatRelative(deltaSec: Double): String {
        val s = deltaSec.toInt().coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return if (m > 0) "%d:%02d".format(m, r) else "0:%02d".format(r)
    }
}
