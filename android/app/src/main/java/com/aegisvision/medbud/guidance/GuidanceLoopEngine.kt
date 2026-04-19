package com.aegisvision.medbud.guidance

import android.util.Log
import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.action.ActionPlanStatus
import com.aegisvision.medbud.action.ActionStep
import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.perception.PerceptionRepository
import com.aegisvision.medbud.perception.nowSec
import com.aegisvision.medbud.voice.ElevenLabsTTSManager
import com.aegisvision.medbud.voice.InstructionLevel
import com.aegisvision.medbud.voice.InstructionMapper
import com.aegisvision.medbud.voice.VoiceInstructionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Phase 3.2 — speak / listen / decide loop.
 *
 * One stateful orchestrator that:
 *   1. observes the Phase-2 StateFlows
 *   2. speaks the current instruction via [ElevenLabsTTSManager]
 *   3. opens a short listening window via [RealtimeResponseListener]
 *   4. interprets the user response via [ResponseInterpreter]
 *   5. decides to advance / repeat / clarify / escalate
 *   6. feeds raw transcripts back into perception fusion so the tracker
 *      can learn from what the user said
 *
 * Not a chatbot. One cycle = speak once, listen once, act once.
 */
class GuidanceLoopEngine(
    private val tts: ElevenLabsTTSManager,
    private val voice: VoiceInstructionEngine,
    private val listener: RealtimeResponseListener,
    private val repo: PerceptionRepository,
) {
    val state: StateFlow<GuidanceLoopState> get() = _state
    private val _state = MutableStateFlow(GuidanceLoopState.initial())

    /** Phase 3.3 — adaptive delivery. Publicly readable for UI / analytics. */
    val adaptation: AdaptationEngine = AdaptationEngine()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var currentCycle: Job? = null

    // Tracking across cycles
    @Volatile private var lastPlanId: String = ""
    @Volatile private var lastUrgency: UrgencyLevel = UrgencyLevel.LOW
    @Volatile private var stepIndex: Int = 0
    @Volatile private var retryCount: Int = 0
    @Volatile private var escalation: EscalationLevel = EscalationLevel.NONE

    // -------------------------------------------------------------- lifecycle

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            combine(
                repo.actionPlanState,
                repo.decisionState,
                repo.clarificationState,
            ) { a, d, c -> Triple(a, d, c) }
                .collect { (plan, decision, clarification) ->
                    onUpdate(plan, decision, clarification)
                }
        }
    }

    fun stop() {
        currentCycle?.cancel()
        loopJob?.cancel()
        tts.interrupt()
        scope.cancel()
    }

    // -------------------------------------------------- inbound state change

    private fun onUpdate(
        plan: ActionPlanState,
        decision: DecisionState,
        clarification: ClarificationState,
    ) {
        val planId = planIdentity(plan)
        val planChanged = planId != lastPlanId
        val urgencyUp = decision.urgency.ordinal > lastUrgency.ordinal

        // Silent / passive statuses — cancel any in-flight cycle and go idle.
        val status = plan.status
        if (status == ActionPlanStatus.NOT_READY || status == ActionPlanStatus.MONITOR_ONLY) {
            if (_state.value.loopStatus != LoopStatus.IDLE) {
                currentCycle?.cancel()
                tts.interrupt()
                _state.value = _state.value.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.IDLE,
                    rationale = "Plan is ${status.name.lowercase()}; holding silent.",
                )
            }
            lastPlanId = planId
            lastUrgency = decision.urgency
            return
        }

        // Start a new cycle if the plan changed, urgency escalated, or we
        // finished a cycle and the plan is still active (retry decision).
        val needNewCycle = planChanged || urgencyUp || currentCycle?.isActive != true
        if (!needNewCycle) {
            lastPlanId = planId
            lastUrgency = decision.urgency
            return
        }

        if (planChanged || urgencyUp) {
            // Interrupt whatever we were doing — the picture changed.
            currentCycle?.cancel()
            tts.interrupt()
            retryCount = 0
            escalation = EscalationLevel.NONE
            if (planChanged) {
                stepIndex = 0
                voice.resetSteps()
            }
        }

        lastPlanId = planId
        lastUrgency = decision.urgency

        currentCycle = scope.launch {
            runCycle(plan, decision, clarification)
        }
    }

    // ------------------------------------------------------ the main cycle

    private suspend fun runCycle(
        plan: ActionPlanState,
        decision: DecisionState,
        clarification: ClarificationState,
    ) {
        val escalationAtStart = escalation
        val retryCountAtStart = retryCount

        // Phase 3.3 — let adaptation see the current context BEFORE we
        // pick a phrase variant or listen window.
        adaptation.reactToContext(decision.urgency, escalationAtStart, retryCountAtStart)
        val adapt = adaptation.state.value

        val step = currentStep(plan) ?: run {
            markIdle("No current step to speak.")
            return
        }
        val urgency = adaptation.effectiveUrgency(decision.urgency)
        val toSpeak = sentenceFor(plan, clarification, step, adapt.instructionComplexity) ?: run {
            markIdle("Nothing to say for step.")
            return
        }

        // ---- SPEAK ----
        pushState { it.copy(
            timestampSec = nowSec(),
            loopStatus = LoopStatus.SPEAKING,
            currentInstructionKey = step.instructionKey,
            currentStepIndex = stepIndex,
            completionState = StepCompletionState.PENDING,
            retryCount = retryCount,
            escalationLevel = escalation,
            rationale = "Speaking (${adapt.currentMode.name.lowercase()}) \"$toSpeak\".",
        ) }
        try {
            tts.speakAwait(toSpeak, urgency)
        } catch (t: Throwable) {
            Log.w(TAG, "speakAwait failed", t)
        }

        // ---- LISTEN ----
        pushState { it.copy(
            timestampSec = nowSec(),
            loopStatus = LoopStatus.LISTENING,
            rationale = "Listening (${adapt.pacing.name.lowercase()} pacing)…",
        ) }
        val listenMs = adaptation.listenWindowMs()
        val result = try {
            listener.listenOnce(listenMs)
        } catch (t: Throwable) {
            Log.w(TAG, "listenOnce failed", t)
            RealtimeResponseListener.Result(UserResponseType.NO_RESPONSE, "")
        }

        // Forward raw transcript to perception fusion so the tracker can
        // learn from it (e.g. "he's breathing" tips the breathing field).
        if (result.rawTranscript.isNotEmpty()) {
            repo.submitTranscript(result.rawTranscript)
        }

        // ---- DECIDE ----
        pushState { it.copy(
            timestampSec = nowSec(),
            loopStatus = LoopStatus.DECIDING,
            lastUserResponse = result.response,
        ) }
        val feedback = decide(step, result.response)

        // Phase 3.3 — record this cycle's outcome for adaptation.
        val speedScore = speedScoreFor(result.response)
        adaptation.recordCycle(
            CycleOutcome(
                success = feedback.action == FeedbackAction.ADVANCE,
                speedScore = speedScore,
                urgency = decision.urgency,
                escalation = escalationAtStart,
                retryCount = retryCountAtStart,
            )
        )

        apply(feedback, decision.urgency)
    }

    /**
     * Rough speed proxy. No VAD yet, so we use response presence as
     * the signal: a concrete reply → 0.7, pure silence → 0.0.
     * Refined in Phase 3.4 when we add streaming STT with timestamps.
     */
    private fun speedScoreFor(response: UserResponseType): Double = when (response) {
        UserResponseType.NO_RESPONSE -> 0.0
        UserResponseType.UNCLEAR -> 0.3
        else -> 0.7
    }

    // ----------------------------------------------------- decision rules

    private fun decide(step: ActionStep, response: UserResponseType): FeedbackDecision {
        return when (response) {
            UserResponseType.YES,
            UserResponseType.DONE,
            UserResponseType.BREATHING_PRESENT,
            UserResponseType.BREATHING_ABSENT,
            UserResponseType.BLEEDING_BETTER -> FeedbackDecision(
                action = FeedbackAction.ADVANCE,
                speakText = null,
                advanceStep = true,
                requestClarification = false,
                interruptCurrentSpeech = false,
                reason = "user confirmed (${response.name.lowercase()})",
            )

            UserResponseType.BLEEDING_WORSE -> FeedbackDecision(
                // Worsening ≠ completed; re-trigger loop with the same step
                // so Phase 2.1/2.3 can re-rank on fresh perception.
                action = FeedbackAction.REPEAT,
                speakText = null,
                advanceStep = false,
                requestClarification = false,
                interruptCurrentSpeech = false,
                reason = "user reports worsening — repeating with fresh state",
            )

            UserResponseType.CANT_DO -> FeedbackDecision(
                action = FeedbackAction.ESCALATE,
                speakText = null,      // apply() will pick the escalation line
                advanceStep = false,
                requestClarification = false,
                interruptCurrentSpeech = true,
                reason = "user cannot perform this step",
            )

            UserResponseType.HELP_REQUEST -> FeedbackDecision(
                action = FeedbackAction.HANDOFF,
                speakText = "Call emergency services now.",
                advanceStep = false,
                requestClarification = false,
                interruptCurrentSpeech = true,
                reason = "user requested help",
            )

            UserResponseType.DONT_KNOW,
            UserResponseType.UNCLEAR,
            UserResponseType.NO_RESPONSE,
            UserResponseType.NO -> {
                if (retryCount < MAX_RETRIES) FeedbackDecision(
                    action = FeedbackAction.REPEAT,
                    speakText = null,
                    advanceStep = false,
                    requestClarification = false,
                    interruptCurrentSpeech = false,
                    reason = "no / unclear response; retrying",
                ) else FeedbackDecision(
                    action = FeedbackAction.ESCALATE,
                    speakText = null,
                    advanceStep = false,
                    requestClarification = false,
                    interruptCurrentSpeech = true,
                    reason = "retry limit reached",
                )
            }
        }
    }

    private fun apply(decision: FeedbackDecision, baseUrgency: UrgencyLevel) {
        when (decision.action) {
            FeedbackAction.ADVANCE -> {
                stepIndex += 1
                voice.advanceStep()     // keep the Phase 3.1 helper in sync
                retryCount = 0
                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.WAITING,
                    completionState = StepCompletionState.COMPLETED,
                    currentStepIndex = stepIndex,
                    retryCount = 0,
                    rationale = "Advanced. ${decision.reason}.",
                ) }
            }

            FeedbackAction.REPEAT -> {
                retryCount += 1
                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.WAITING,
                    completionState = StepCompletionState.PENDING,
                    retryCount = retryCount,
                    rationale = "Repeating. ${decision.reason}.",
                ) }
            }

            FeedbackAction.ESCALATE -> {
                escalation = bumpEscalation(escalation)
                retryCount = 0
                val line = when (escalation) {
                    EscalationLevel.ELEVATED -> "Try again. Tell me when done."
                    EscalationLevel.URGENT -> "Focus. One word: done, or can't."
                    EscalationLevel.HANDOFF -> "Call emergency services now."
                    else -> "Try again."
                }
                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.ESCALATED,
                    escalationLevel = escalation,
                    retryCount = 0,
                    rationale = "Escalated to ${escalation.name.lowercase()}. ${decision.reason}.",
                ) }
                if (decision.interruptCurrentSpeech) tts.interrupt()
                scope.launch {
                    tts.speakAwait(line, escalatedUrgency(baseUrgency, escalation))
                }
            }

            FeedbackAction.HANDOFF -> {
                escalation = EscalationLevel.HANDOFF
                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.HANDOFF,
                    escalationLevel = EscalationLevel.HANDOFF,
                    completionState = StepCompletionState.SKIPPED,
                    rationale = "Handoff triggered. ${decision.reason}.",
                ) }
                if (decision.interruptCurrentSpeech) tts.interrupt()
                scope.launch {
                    tts.speakAwait(decision.speakText ?: "Call emergency services now.",
                                   UrgencyLevel.CRITICAL)
                }
            }

            FeedbackAction.CLARIFY -> {
                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.WAITING,
                    rationale = "Awaiting clarification. ${decision.reason}.",
                ) }
            }

            FeedbackAction.CONTINUE_SILENT -> {
                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.IDLE,
                    rationale = "Silent continuation. ${decision.reason}.",
                ) }
            }
        }
    }

    // --------------------------------------------------------- helpers

    private fun currentStep(plan: ActionPlanState): ActionStep? {
        if (plan.plannedSteps.isEmpty()) return null
        val idx = stepIndex.coerceAtMost(plan.plannedSteps.lastIndex)
        return plan.plannedSteps.getOrNull(idx)
    }

    /**
     * What to actually say for this cycle. Respects plan status (speak
     * clarification prompt in CLARIFY_FIRST), escalation (terser), and
     * Phase 3.3 [InstructionLevel] (FULL / SHORT / MINIMAL phrase variant).
     */
    private fun sentenceFor(
        plan: ActionPlanState,
        clarification: ClarificationState,
        step: ActionStep,
        level: InstructionLevel,
    ): String? {
        if (plan.status == ActionPlanStatus.CLARIFY_FIRST) {
            val prompt = clarification.recommendedPrompt.promptText
            return prompt.ifBlank { null }
        }
        val base = InstructionMapper.phraseFor(step.instructionKey, level)
        // At MINIMAL level we strip any leading prefix — the whole point
        // of minimal mode is to keep output to 1–3 words.
        if (level == InstructionLevel.MINIMAL) return base
        val prefix = when {
            plan.status == ActionPlanStatus.READY_WITH_CAUTION &&
                escalation == EscalationLevel.NONE &&
                plan.safetyFlags.isNotEmpty() -> "Careful. "
            escalation == EscalationLevel.URGENT -> "Now. "
            else -> ""
        }
        return prefix + base
    }

    private fun planIdentity(plan: ActionPlanState): String = buildString {
        append(plan.primaryAction.name); append('|')
        plan.plannedSteps.joinTo(this, ",") { it.instructionKey }
    }

    /**
     * Urgency used when speaking an escalation line directly (e.g.
     * "Try again. Tell me when done."). Mirrors the old logic so the
     * ESCALATE path keeps its existing behaviour; general step speech
     * goes through [adaptation].[AdaptationEngine.effectiveUrgency].
     */
    private fun escalatedUrgency(base: UrgencyLevel, esc: EscalationLevel): UrgencyLevel =
        when (esc) {
            EscalationLevel.NONE -> base
            EscalationLevel.ELEVATED ->
                if (base.ordinal >= UrgencyLevel.HIGH.ordinal) base else UrgencyLevel.HIGH
            EscalationLevel.URGENT,
            EscalationLevel.HANDOFF -> UrgencyLevel.CRITICAL
        }

    private fun bumpEscalation(current: EscalationLevel): EscalationLevel = when (current) {
        EscalationLevel.NONE -> EscalationLevel.ELEVATED
        EscalationLevel.ELEVATED -> EscalationLevel.URGENT
        EscalationLevel.URGENT -> EscalationLevel.HANDOFF
        EscalationLevel.HANDOFF -> EscalationLevel.HANDOFF
    }

    private fun markIdle(reason: String) {
        pushState { it.copy(
            timestampSec = nowSec(),
            loopStatus = LoopStatus.IDLE,
            rationale = reason,
        ) }
    }

    private inline fun pushState(update: (GuidanceLoopState) -> GuidanceLoopState) {
        _state.value = update(_state.value)
    }

    companion object {
        private const val TAG = "GuidanceLoop"
        private const val MAX_RETRIES = 2
    }
}

