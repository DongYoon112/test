package com.aegisvision.medbud.report

import android.util.Log
import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.action.ActionType
import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.clarification.ClarificationType
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.PriorityType
import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.guidance.EscalationLevel
import com.aegisvision.medbud.guidance.GuidanceLoopEngine
import com.aegisvision.medbud.guidance.GuidanceLoopState
import com.aegisvision.medbud.guidance.LoopStatus
import com.aegisvision.medbud.guidance.StepCompletionState
import com.aegisvision.medbud.perception.PerceptionRepository
import com.aegisvision.medbud.perception.PerceptionState
import com.aegisvision.medbud.perception.Source
import com.aegisvision.medbud.perception.nowSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Subscribes to the Phase 1-3 StateFlows, decides which transitions
 * matter, and pushes them into [IncidentLogger] as [TimelineEvent]s.
 *
 * Event-logging rules (intentionally narrow — this is a handoff, not a
 * debug trace):
 *
 *   PATIENT_DETECTED         personVisible "no/unknown" → "yes"
 *   PATIENT_LOST             personVisible "yes" → "no" (sustained)
 *   BLEEDING_OBSERVED        bleeding none/unknown → minor|heavy
 *   BLEEDING_RESOLVED        bleeding minor|heavy → none
 *   BREATHING_CONCERN        breathing normal/unknown → abnormal|none
 *   BREATHING_NORMAL         breathing abnormal|none → normal
 *   CONSCIOUSNESS_CONCERN    conscious yes/unknown → no
 *   CONSCIOUSNESS_NORMAL     conscious no → yes
 *   SCENE_RISK               any new risk (deduped across session)
 *   PRIORITY_CHANGE          DecisionState.primaryPriority changes
 *   ACTION_CHANGE            ActionPlanState.primaryAction changes
 *   STEP_COMPLETED           stepIndex advanced AND completionState=COMPLETED
 *   ESCALATION               EscalationLevel rose
 *   HANDOFF_TRIGGERED        loopStatus = HANDOFF (once)
 *   CLARIFICATION_REQUESTED  primaryClarificationNeed type flips to non-NONE
 *   USER_REPORT              lastUserResponse changes to a concrete type
 *
 * Everything else (confidence drift, step repeats, identical re-emits)
 * is silently dropped so the timeline stays readable.
 */
class IncidentBridge(
    private val logger: IncidentLogger,
    private val repo: PerceptionRepository,
    private val guidance: GuidanceLoopEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs: MutableList<Job> = mutableListOf()

    // ── last-known values for dedup ──
    private var lastPersonVisible: String = "unknown"
    private var lastBleeding: String = "unknown"
    private var lastBreathing: String = "unknown"
    private var lastConscious: String = "unknown"
    private val loggedSceneRisks = mutableSetOf<String>()
    private var lastPriority: PriorityType? = null
    private var lastUrgency: UrgencyLevel? = null
    private var lastAction: ActionType? = null
    private var lastStepIndex: Int = -1
    private var lastCompletion: StepCompletionState = StepCompletionState.PENDING
    private var lastEscalation: EscalationLevel = EscalationLevel.NONE
    private var lastLoopStatus: LoopStatus? = null
    private var lastClarification: ClarificationType? = null
    private var lastUserResponseTs: Double = 0.0

    fun start() {
        resetMemory()
        jobs += scope.launch { repo.state.collect(::onPerception) }
        jobs += scope.launch { repo.decisionState.collect(::onDecision) }
        jobs += scope.launch { repo.clarificationState.collect(::onClarification) }
        jobs += scope.launch { repo.actionPlanState.collect(::onPlan) }
        jobs += scope.launch { guidance.state.collect(::onGuidance) }
        Log.i(TAG, "bridge started")
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        Log.i(TAG, "bridge stopped")
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun resetMemory() {
        lastPersonVisible = "unknown"
        lastBleeding = "unknown"
        lastBreathing = "unknown"
        lastConscious = "unknown"
        loggedSceneRisks.clear()
        lastPriority = null
        lastUrgency = null
        lastAction = null
        lastStepIndex = -1
        lastCompletion = StepCompletionState.PENDING
        lastEscalation = EscalationLevel.NONE
        lastLoopStatus = null
        lastClarification = null
        lastUserResponseTs = 0.0
    }

    // ──────────────────────────────────────────────────────── Perception

    private fun onPerception(p: PerceptionState) {
        // Person visible transitions.
        val pv = p.personVisible.value
        if (pv != lastPersonVisible) {
            if (pv == "yes" && lastPersonVisible != "yes") {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.PATIENT_DETECTED,
                    title = "Patient detected",
                    details = "person visible with confidence ${fmt(p.personVisible.confidence)}",
                    source = sourceOf(p.personVisible.source),
                ))
            } else if (pv == "no" && lastPersonVisible == "yes" &&
                p.personVisible.confidence >= 0.5) {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.PATIENT_LOST,
                    title = "Patient out of frame",
                    details = "camera no longer shows a person",
                    source = EventSource.SYSTEM_OBSERVED,
                ))
            }
            lastPersonVisible = pv
        }

        // Bleeding transitions.
        val bl = p.bleeding.value
        if (bl != lastBleeding) {
            val wasActive = lastBleeding == "minor" || lastBleeding == "heavy"
            val isActive = bl == "minor" || bl == "heavy"
            if (isActive && !wasActive) {
                val severity = if (bl == "heavy") "heavy" else "minor"
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.BLEEDING_OBSERVED,
                    title = "Bleeding detected ($severity)",
                    details = "bleeding=$severity, confidence=${fmt(p.bleeding.confidence)}",
                    source = sourceOf(p.bleeding.source),
                ))
            } else if (!isActive && wasActive && bl == "none") {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.BLEEDING_RESOLVED,
                    title = "Bleeding resolved",
                    details = "confidence=${fmt(p.bleeding.confidence)}",
                    source = sourceOf(p.bleeding.source),
                ))
            }
            lastBleeding = bl
        }

        // Breathing transitions.
        val br = p.breathing.value
        if (br != lastBreathing) {
            val wasConcern = lastBreathing == "abnormal" || lastBreathing == "none"
            val isConcern = br == "abnormal" || br == "none"
            if (isConcern && !wasConcern) {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.BREATHING_CONCERN,
                    title = "Breathing concern",
                    details = "breathing=$br, confidence=${fmt(p.breathing.confidence)}" +
                        if (p.breathing.source == Source.SPEECH) " (user-reported, weak visual)" else "",
                    source = sourceOf(p.breathing.source),
                ))
            } else if (!isConcern && wasConcern && br == "normal") {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.BREATHING_NORMAL,
                    title = "Breathing normal",
                    details = "confidence=${fmt(p.breathing.confidence)}",
                    source = sourceOf(p.breathing.source),
                ))
            }
            lastBreathing = br
        }

        // Consciousness transitions.
        val cs = p.conscious.value
        if (cs != lastConscious) {
            if (cs == "no" && lastConscious != "no") {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.CONSCIOUSNESS_CONCERN,
                    title = "Patient unresponsive",
                    details = "conscious=no, confidence=${fmt(p.conscious.confidence)}" +
                        if (p.conscious.source == Source.SPEECH) " (user-reported)" else "",
                    source = sourceOf(p.conscious.source),
                ))
            } else if (cs == "yes" && lastConscious == "no") {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.CONSCIOUSNESS_NORMAL,
                    title = "Patient responsive",
                    details = "confidence=${fmt(p.conscious.confidence)}",
                    source = sourceOf(p.conscious.source),
                ))
            }
            lastConscious = cs
        }

        // Scene risks — once per risk per session.
        for (risk in p.sceneRisk) {
            if (risk == "none" || risk.isBlank()) continue
            if (loggedSceneRisks.add(risk)) {
                logger.record(TimelineEvent(
                    timestampSec = nowSec(),
                    type = TimelineEventType.SCENE_RISK,
                    title = "Scene risk: $risk",
                    details = "observed in frame",
                    source = EventSource.SYSTEM_OBSERVED,
                ))
            }
        }
    }

    // ──────────────────────────────────────────────────────── Decision

    private fun onDecision(d: DecisionState) {
        if (lastPriority != null && d.primaryPriority != lastPriority) {
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.PRIORITY_CHANGE,
                title = "Priority → ${d.primaryPriority.name.lowercase()}",
                details = "urgency=${d.urgency.name.lowercase()}, confidence=${fmt(d.confidence)}",
                source = EventSource.SYSTEM_INFERRED,
            ))
        }
        lastPriority = d.primaryPriority
        lastUrgency = d.urgency
    }

    // ──────────────────────────────────────────────────────── Clarification

    private fun onClarification(c: ClarificationState) {
        val t = c.primaryClarificationNeed.type
        if (t != lastClarification &&
            t != ClarificationType.NO_CLARIFICATION_NEEDED) {
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.CLARIFICATION_REQUESTED,
                title = "Clarification: ${t.name.lowercase()}",
                details = c.primaryClarificationNeed.reason.take(120),
                source = EventSource.SYSTEM_INFERRED,
            ))
        }
        lastClarification = t
    }

    // ──────────────────────────────────────────────────────── Plan

    private fun onPlan(plan: ActionPlanState) {
        if (lastAction != null && plan.primaryAction != lastAction) {
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.ACTION_CHANGE,
                title = "Action → ${plan.primaryAction.name.lowercase()}",
                details = "status=${plan.status.name.lowercase()}; " +
                    "steps=${plan.plannedSteps.joinToString(" → ") { it.instructionKey }}",
                source = EventSource.SYSTEM_INFERRED,
            ))
        }
        lastAction = plan.primaryAction
    }

    // ──────────────────────────────────────────────────────── Guidance

    private fun onGuidance(g: GuidanceLoopState) {
        // Step completion — only when stepIndex actually advanced.
        if (g.completionState == StepCompletionState.COMPLETED &&
            g.currentStepIndex > lastStepIndex) {
            val key = g.currentInstructionKey ?: "step"
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.STEP_COMPLETED,
                title = "Step done: ${humanizeKey(key)}",
                details = "user response=${g.lastUserResponse.name.lowercase()}",
                source = EventSource.ACTION_TAKEN,
            ))
        }
        if (g.currentStepIndex != lastStepIndex) lastStepIndex = g.currentStepIndex
        lastCompletion = g.completionState

        // Escalation — only when it actually rose.
        if (g.escalationLevel.ordinal > lastEscalation.ordinal) {
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.ESCALATION,
                title = "Escalation: ${g.escalationLevel.name.lowercase()}",
                details = "retryCount=${g.retryCount}; ${g.rationale.take(100)}",
                source = EventSource.SYSTEM_INFERRED,
            ))
        }
        lastEscalation = g.escalationLevel

        // Handoff — once per session.
        if (g.loopStatus == LoopStatus.HANDOFF && lastLoopStatus != LoopStatus.HANDOFF) {
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.HANDOFF_TRIGGERED,
                title = "Handoff triggered",
                details = g.rationale.take(140),
                source = EventSource.SYSTEM_INFERRED,
            ))
        }
        lastLoopStatus = g.loopStatus

        // User report — when lastUserResponse changes at a new timestamp.
        val isConcrete = g.lastUserResponse.name !in IGNORED_USER_RESPONSES
        if (isConcrete && g.timestampSec > lastUserResponseTs) {
            logger.record(TimelineEvent(
                timestampSec = nowSec(),
                type = TimelineEventType.USER_REPORT,
                title = "User: ${g.lastUserResponse.name.lowercase().replace('_', ' ')}",
                details = g.rationale.take(120),
                source = EventSource.USER_REPORTED,
            ))
            lastUserResponseTs = g.timestampSec
        }
    }

    // ──────────────────────────────────────────────────────── helpers

    private fun sourceOf(s: Source): EventSource = when (s) {
        Source.VISION -> EventSource.SYSTEM_OBSERVED
        Source.SPEECH -> EventSource.USER_REPORTED
        Source.FUSED  -> EventSource.SYSTEM_INFERRED
    }

    private fun humanizeKey(key: String): String =
        key.replace('_', ' ')

    private fun fmt(d: Double): String = "%.2f".format(d)

    companion object {
        private const val TAG = "IncidentBridge"
        // Responses we don't log as user-visible events — silence and
        // parser ambiguity aren't handoff-relevant facts.
        private val IGNORED_USER_RESPONSES = setOf(
            "NO_RESPONSE", "UNCLEAR", "DONT_KNOW",
        )
    }
}
