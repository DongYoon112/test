package com.aegisvision.medbud.guidance

import android.util.Log
import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.action.ActionPlanStatus
import com.aegisvision.medbud.action.ActionStep
import com.aegisvision.medbud.action.ActionType
import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.perception.PerceptionRepository
import com.aegisvision.medbud.perception.PerceptionState
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
import kotlinx.coroutines.delay
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
    @Volatile private var manualWakeActive: Boolean = false
    @Volatile private var planCompleted: Boolean = false

    // When false, the loop ignores state updates, utterances, and pending
    // cycles — nothing speaks. Flipped by setActive() from MainActivity
    // around the DAT stream's lifecycle so audio can't leak out after the
    // user pressed "Stop Stream".
    @Volatile private var active: Boolean = false

    // Latched once a critical patient-state utterance triggers the CPR /
    // 911 handoff. Unlike planCompleted, this survives plan-identity
    // changes — we don't want to fall back to "Point the camera at the
    // chest" five seconds after telling the user to start chest
    // compressions. Cleared only by reset() or an explicit "responsive"
    // or "breathing" confirmation from the user.
    @Volatile private var handoffLatched: Boolean = false

    // Last step we've actually spoken aloud in the current plan. Prevents
    // re-speaking the same instruction every time an unrelated perception
    // field twitches and re-emits the plan state.
    @Volatile private var lastSpokenStepIndex: Int = -1
    @Volatile private var lastSpokenEscalation: EscalationLevel = EscalationLevel.NONE

    // Debounce job for cycle launches. The VLM pipeline lands a burst of
    // state updates (perception → decision → clarification → plan) within
    // a few hundred ms; without a debounce, each one cancels the previous
    // TTS and you get clipped / overlapping speech.
    @Volatile private var pendingCycleJob: Job? = null

    // When the most recent utterance actually started playing. Used to
    // enforce a minimum protected speaking window before a state-driven
    // relaunch can interrupt.
    @Volatile private var lastSpeakStartMs: Long = 0L

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
        // Always-on mic drives BOTH fusion and step progression. A user
        // utterance at ANY time (between cycles, while AI idle, etc.) can
        // advance / escalate — no more waiting for a listen window.
        scope.launch {
            listener.transcripts.collect { r ->
                if (r.rawTranscript.isNotEmpty()) {
                    repo.submitTranscript(r.rawTranscript)
                    Log.i(TAG, "auto-fused: [${r.response.name}] \"${r.rawTranscript.take(80)}\"")
                }
                handleContinuousUtterance(r)
            }
        }
    }

    /**
     * Drive step transitions from any utterance, regardless of whether
     * a cycle is "listening". Coordinates with the active cycle so we
     * don't double-advance when the cycle was about to do it anyway.
     */
    private fun handleContinuousUtterance(r: RealtimeResponseListener.Result) {
        if (!active) return
        val plan = repo.actionPlanState.value
        // LOCATE_PATIENT is treated as silent: no step prompts, no
        // advancement-on-utterance. We just keep listening until a real
        // medical plan emerges.
        if (plan.status == ActionPlanStatus.NOT_READY ||
            plan.status == ActionPlanStatus.MONITOR_ONLY ||
            plan.primaryAction == ActionType.LOCATE_PATIENT) return
        val step = currentStep(plan) ?: return
        val feedback = decide(step, r.response)

        // Ignore no-ops so silence / unclear doesn't churn the adaptation
        // engine or force re-speaks.
        if (r.response == UserResponseType.NO_RESPONSE ||
            r.response == UserResponseType.UNCLEAR ||
            r.response == UserResponseType.DONT_KNOW ||
            r.response == UserResponseType.NO) return

        Log.i(TAG, "utterance → ${feedback.action.name}: ${feedback.reason}")

        adaptation.recordCycle(
            CycleOutcome(
                success = feedback.action == FeedbackAction.ADVANCE,
                speedScore = speedScoreFor(r.response),
                urgency = repo.decisionState.value.urgency,
                escalation = escalation,
                retryCount = retryCount,
            )
        )

        pushState { it.copy(
            timestampSec = nowSec(),
            loopStatus = LoopStatus.DECIDING,
            lastUserResponse = r.response,
            rationale = "Utterance → ${feedback.action.name.lowercase()}: ${feedback.reason}",
        ) }

        // A "resolution" utterance short-circuits to the plan's ending from
        // any step, not just the last one. If the user already knows the
        // bleeding stopped / patient is breathing / etc., there's no value
        // in walking them through the remaining reassess steps.
        //
        // Critical patient-state cues ("not breathing", "unresponsive") are
        // plan-agnostic — the transcript hasn't hit the VLM pipeline yet, so
        // `plan.primaryAction` may still reflect stale priorities. Route
        // those straight to a CPR / 911 handoff regardless of the plan.
        val lastIndex = plan.plannedSteps.lastIndex
        val critical = criticalResolution(r.response)
        val resolution = critical ?: resolutionFor(plan.primaryAction, r.response)
        val isTerminal = feedback.action == FeedbackAction.ADVANCE &&
            (stepIndex >= lastIndex || resolution != null)
        if (isTerminal) {
            planCompleted = true
            if (critical != null) handoffLatched = true
            // Recovery utterances (breathing/responsive confirmed) actively
            // clear a prior handoff latch so the loop can resume normal
            // guidance after a false-alarm.
            if (r.response == UserResponseType.BREATHING_PRESENT ||
                r.response == UserResponseType.RESPONSIVE_CONFIRMED) {
                handoffLatched = false
            }
            stepIndex = plan.plannedSteps.size  // out of range on purpose
            voice.advanceStep()
            retryCount = 0
            currentCycle?.cancel()
            tts.interrupt()
            pushState { it.copy(
                timestampSec = nowSec(),
                loopStatus = LoopStatus.WAITING,
                completionState = StepCompletionState.COMPLETED,
                currentStepIndex = stepIndex,
                rationale = "Plan complete; user confirmed (${r.response.name.lowercase()}).",
            ) }
            val (closer, closerUrgency) = resolution
                ?: ("Good. Keep watching them and tell me if anything changes." to UrgencyLevel.LOW)
            currentCycle = scope.launch {
                voice.publishSpokenLine(closer, closerUrgency)
                try { tts.speakAwait(closer, closerUrgency) } catch (_: Throwable) {}
            }
            return
        }

        apply(feedback, repo.decisionState.value.urgency)

        // After an advancement (or escalation), speak the next instruction.
        if (feedback.action == FeedbackAction.ADVANCE ||
            feedback.action == FeedbackAction.REPEAT) {
            triggerFreshCycle()
        }
    }

    private fun triggerFreshCycle() {
        // User-driven transition: speak immediately, no debounce delay.
        // The user just told us to advance — holding their instruction
        // for 600ms would feel broken.
        pendingCycleJob?.cancel()
        currentCycle?.cancel()
        currentCycle = scope.launch {
            runCycle(
                repo.actionPlanState.value,
                repo.decisionState.value,
                repo.clarificationState.value,
            )
        }
    }

    fun stop() {
        pendingCycleJob?.cancel()
        currentCycle?.cancel()
        loopJob?.cancel()
        // Shutdown — force-interrupt any in-flight speech.
        tts.forceInterrupt()
        scope.cancel()
    }

    /**
     * Wipe all per-session state so a fresh Start Stream begins from
     * step zero with a clean retry/escalation record. Does NOT touch the
     * perception repo — frames decay naturally over `maxFrameAgeSec`.
     * Called from MainActivity on both stop and re-start.
     */
    /**
     * Enable or disable the loop. While inactive, any in-flight TTS is
     * interrupted and no future state update, utterance, or cycle can
     * produce audio. MainActivity calls `setActive(true)` on stream start
     * and `setActive(false)` on stream stop.
     */
    fun setActive(value: Boolean) {
        active = value
        if (!value) {
            Log.i(TAG, "guidance deactivated — killing any audio")
            pendingCycleJob?.cancel()
            pendingCycleJob = null
            currentCycle?.cancel()
            currentCycle = null
            // User-driven stop — force-interrupt even mid-sentence.
            tts.forceInterrupt()
        }
    }

    fun reset() {
        Log.i(TAG, "guidance reset()")
        pendingCycleJob?.cancel()
        pendingCycleJob = null
        currentCycle?.cancel()
        currentCycle = null
        // User-driven reset — force-interrupt even mid-sentence.
        tts.forceInterrupt()

        lastPlanId = ""
        lastUrgency = UrgencyLevel.LOW
        stepIndex = 0
        retryCount = 0
        escalation = EscalationLevel.NONE
        manualWakeActive = false
        planCompleted = false
        handoffLatched = false
        lastSpokenStepIndex = -1
        lastSpokenEscalation = EscalationLevel.NONE
        lastSpeakStartMs = 0L

        voice.resetSteps()
        _state.value = GuidanceLoopState.initial()
    }

    /**
     * Demo / manual trigger — the "wake" path.
     *
     * Forces the loop to speak a short greeting, open a listening window,
     * and feed whatever the user says into the perception fusion engine.
     * That transcript updates the tracker, which changes the plan, which
     * then drives the normal speak/listen/decide loop on the next frame.
     *
     * Safe to call anytime: cancels any in-flight cycle first.
     */
    fun triggerManualWake() {
        if (!active) {
            Log.i(TAG, "triggerManualWake() ignored — guidance inactive")
            return
        }
        currentCycle?.cancel()
        tts.interrupt()
        Log.i(TAG, "triggerManualWake() — Ask AI button pressed")
        currentCycle = scope.launch {
            manualWakeActive = true
            try {
                // Always-speak greeting — irrespective of plan state.
                val greet = "Hello. How can I help?"
                voice.publishSpokenLine(greet, UrgencyLevel.MODERATE)
                Log.i(TAG, "TTS wake greet: $greet")
                try {
                    tts.speakAwait(greet, UrgencyLevel.MODERATE)
                } catch (t: Throwable) {
                    Log.e(TAG, "wake greet speakAwait threw", t)
                }

                val result = try {
                    listener.listenOnce(5_000L)
                } catch (t: Throwable) {
                    Log.w(TAG, "manual wake listen failed", t)
                    RealtimeResponseListener.Result(UserResponseType.NO_RESPONSE, "")
                }
                Log.i(TAG, "wake heard: ${result.response.name} text=\"${result.rawTranscript.take(100)}\"")
                if (result.rawTranscript.isNotEmpty()) {
                    repo.submitTranscript(result.rawTranscript)
                }

                // Always speak a follow-up.
                val immediate = immediateWakeResponse(result.response)
                    ?: "Okay. Tell me more when you need me."
                voice.publishSpokenLine(immediate, UrgencyLevel.HIGH)
                Log.i(TAG, "TTS wake reply: $immediate")
                try {
                    tts.speakAwait(immediate, UrgencyLevel.HIGH)
                } catch (t: Throwable) {
                    Log.e(TAG, "wake reply speakAwait threw", t)
                }

                pushState { it.copy(
                    timestampSec = nowSec(),
                    loopStatus = LoopStatus.WAITING,
                    lastUserResponse = result.response,
                    rationale = "Manual wake; heard=${result.response.name.lowercase()} " +
                        "text=\"${result.rawTranscript.take(60)}\".",
                ) }
            } catch (t: Throwable) {
                Log.e(TAG, "triggerManualWake failed", t)
            } finally {
                manualWakeActive = false
            }
        }
    }

    /**
     * Context-aware one-liner based on what the user just said. Keeps the
     * manual-wake path responsive even when the planner is still digesting
     * the transcript (< 1 s perception lag).
     */
    private fun immediateWakeResponse(resp: UserResponseType): String? = when (resp) {
        UserResponseType.HELP_REQUEST -> "Call emergency services now."
        UserResponseType.BREATHING_ABSENT -> "Point the camera at the chest."
        UserResponseType.BREATHING_PRESENT -> "Keep watching. Check again in a moment."
        UserResponseType.BLEEDING_WORSE -> "Show me where the bleeding is."
        UserResponseType.BLEEDING_BETTER -> "Good. Keep light pressure. Call 911 if it starts again."
        UserResponseType.UNRESPONSIVE_CONFIRMED -> "Call 911 now. Tell them they're unresponsive."
        UserResponseType.RESPONSIVE_CONFIRMED -> "Good. Stay with them and keep them calm."
        UserResponseType.CANT_DO -> "Describe what you see."
        UserResponseType.DONT_KNOW, UserResponseType.UNCLEAR ->
            "Tell me what happened."
        UserResponseType.NO_RESPONSE -> "I didn't hear you. Try again."
        UserResponseType.YES, UserResponseType.NO, UserResponseType.DONE -> null
    }

    // -------------------------------------------------- inbound state change

    private fun onUpdate(
        plan: ActionPlanState,
        decision: DecisionState,
        clarification: ClarificationState,
    ) {
        if (!active) return
        val planId = planIdentity(plan)
        val planChanged = planId != lastPlanId
        val urgencyUp = decision.urgency.ordinal > lastUrgency.ordinal

        // Silent / passive statuses — cancel any in-flight cycle and go idle.
        // Exception: never cancel an in-flight manual-wake cycle; it's the
        // user's explicit request and must always get a reply. Also honour
        // SPEAK_PROTECT_MS — a single NOT_READY frame shouldn't clip a
        // sentence that just started.
        val status = plan.status
        val silentStatus = status == ActionPlanStatus.NOT_READY ||
            status == ActionPlanStatus.MONITOR_ONLY ||
            plan.primaryAction == ActionType.LOCATE_PATIENT
        if (silentStatus) {
            val sinceSpeak = System.currentTimeMillis() - lastSpeakStartMs
            val stillProtected = currentCycle?.isActive == true &&
                sinceSpeak < SPEAK_PROTECT_MS
            if (_state.value.loopStatus != LoopStatus.IDLE &&
                !manualWakeActive &&
                !stillProtected) {
                currentCycle?.cancel()
                pendingCycleJob?.cancel()
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

        // If a critical CPR / 911 handoff is latched, stay silent through
        // any plan churn. The VLM pipeline catching up to what the user
        // already told us shouldn't un-handoff them.
        if (handoffLatched) {
            lastPlanId = planId
            lastUrgency = decision.urgency
            return
        }

        if (planChanged) {
            // Plan actually changed — the picture changed. Interrupt.
            currentCycle?.cancel()
            pendingCycleJob?.cancel()
            tts.interrupt()
            retryCount = 0
            escalation = EscalationLevel.NONE
            stepIndex = 0
            voice.resetSteps()
            planCompleted = false
            lastSpokenStepIndex = -1
            lastSpokenEscalation = EscalationLevel.NONE
        } else if (urgencyUp) {
            // Urgency bumped but the plan and step are the same. Don't
            // interrupt the in-flight utterance — ElevenLabs voice settings
            // are locked at the start anyway, and clipping a sentence mid-
            // word just to restart it louder sounds worse than letting it
            // finish. The next cycle will use the higher urgency.
            retryCount = 0
        }

        // Suppress further cycles once the user has worked through the
        // whole plan — don't re-speak the last step after the user said
        // "it stopped".
        if (planCompleted && !planChanged && !urgencyUp) {
            lastPlanId = planId
            lastUrgency = decision.urgency
            return
        }

        // Perception-driven auto-advance: if the camera already shows what
        // the current step asks for, skip it. Prevents the loop from
        // repeating "Show the bleeding" after the bleeding is on screen.
        val perception = repo.state.value
        var didAutoAdvance = false
        while (stepIndex < plan.plannedSteps.size - 1) {
            val step = plan.plannedSteps[stepIndex]
            if (isStepCompleteByPerception(step.instructionKey, perception)) {
                Log.i(TAG, "auto-advance: ${step.instructionKey} satisfied by perception")
                stepIndex += 1
                voice.advanceStep()
                retryCount = 0
                didAutoAdvance = true
            } else break
        }

        lastPlanId = planId
        lastUrgency = decision.urgency

        // Don't re-speak if nothing material changed: same step, same
        // escalation, and we already delivered this step once. Unrelated
        // perception twitches (a confidence drift, a new frame) otherwise
        // re-enter this path and re-speak the same instruction forever.
        val nothingNew = !planChanged && !urgencyUp &&
            stepIndex == lastSpokenStepIndex &&
            escalation == lastSpokenEscalation
        if (nothingNew) return

        // If a cycle is already speaking the current step, let it finish —
        // rapid restarts produce the "burst of clipped responses" the user
        // saw when the VLM pipeline landed a flurry of updates.
        if (currentCycle?.isActive == true &&
            stepIndex == lastSpokenStepIndex &&
            escalation == lastSpokenEscalation) return

        scheduleCycle()
    }

    /**
     * Debounce cycle launches. The combine() collector fires once per
     * upstream emission, and the Phase-2 pipeline tends to emit several
     * correlated state updates in a sub-second burst after a VLM response
     * lands. Coalescing them into a single delayed launch means we speak
     * once, with the settled state, instead of starting and canceling
     * TTS three times in a row.
     *
     * Also enforces a minimum "protected speaking window" — once a cycle
     * has actually started speaking, another state-driven cycle launch
     * must wait [SPEAK_PROTECT_MS] before it can interrupt. Keeps a single
     * coherent sentence from being clipped by a late VLM update.
     */
    private fun scheduleCycle() {
        if (!active) return
        pendingCycleJob?.cancel()
        pendingCycleJob = scope.launch {
            delay(CYCLE_DEBOUNCE_MS)
            if (!active) return@launch

            // If we're already inside the protected window of a cycle we
            // recently started, push ourselves out further rather than
            // canceling. The user would rather hear one full sentence
            // than a clipped burst.
            val sinceSpeak = System.currentTimeMillis() - lastSpeakStartMs
            if (currentCycle?.isActive == true && sinceSpeak < SPEAK_PROTECT_MS) {
                delay(SPEAK_PROTECT_MS - sinceSpeak)
            }
            if (!active) return@launch

            // Re-check after waiting: another advance / utterance may have
            // superseded us, or the in-flight cycle is still speaking the
            // same thing we would speak.
            if (currentCycle?.isActive == true &&
                stepIndex == lastSpokenStepIndex &&
                escalation == lastSpokenEscalation) return@launch

            currentCycle?.cancel()
            currentCycle = scope.launch {
                runCycle(
                    repo.actionPlanState.value,
                    repo.decisionState.value,
                    repo.clarificationState.value,
                )
            }
        }
    }

    /**
     * Observation-only steps that can be considered "done" once perception
     * already sees what the step is asking about. Interventional steps
     * (apply pressure, clear airway, etc.) are NOT in this list — those
     * still require user confirmation.
     */
    private fun isStepCompleteByPerception(key: String, p: PerceptionState): Boolean {
        val woundParts = setOf(
            "arm", "leg", "hand", "foot", "chest", "abdomen", "head", "neck", "back",
        )
        return when (key) {
            "locate_bleeding_source" ->
                p.bleeding.confidence >= 0.35 &&
                    p.bodyPartsVisible.any { it in woundParts }
            "locate_chest" -> "chest" in p.bodyPartsVisible
            "locate_patient", "scan_surroundings" ->
                p.personVisible.value == "yes" && p.personVisible.confidence >= 0.4
            "observe_patient", "confirm_patient_visible" ->
                p.personVisible.value == "yes" && p.personVisible.confidence >= 0.5
            "observe_airway" ->
                "face" in p.bodyPartsVisible || "neck" in p.bodyPartsVisible
            "assess_scene" -> p.framesInBuffer >= 3
            "identify_hazard" -> p.sceneRisk.isNotEmpty()
            "gather_more_frames" -> p.framesInBuffer >= 5
            else -> false  // intervention/reassess steps need explicit confirmation
        }
    }

    // ------------------------------------------------------ the main cycle

    private suspend fun runCycle(
        plan: ActionPlanState,
        decision: DecisionState,
        clarification: ClarificationState,
    ) {
        if (!active) return
        // LOCATE_PATIENT has no useful spoken guidance at this stage —
        // "point the camera at whoever needs help" adds noise when the
        // upstream signal is usually wrong anyway (close-ups of a wound
        // often register as person_visible=no). Stay silent.
        if (plan.primaryAction == ActionType.LOCATE_PATIENT) {
            markIdle("Plan is locate_patient; holding silent.")
            return
        }
        adaptation.reactToContext(decision.urgency, escalation, retryCount)
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

        // Speak once. No listen loop, no auto-repeat. State transitions
        // (advance / escalate / handoff) are driven exclusively by the
        // continuous transcript handler + perception auto-advance, so the
        // AI doesn't nag the user once an instruction is delivered.
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
        // Mark this step as spoken so onUpdate won't re-fire it on the
        // next perception tick.
        lastSpokenStepIndex = stepIndex
        lastSpokenEscalation = escalation
        lastSpeakStartMs = System.currentTimeMillis()
        voice.publishSpokenLine(toSpeak, urgency)
        Log.i(TAG, "TTS speak (${urgency.name}): $toSpeak")
        try {
            tts.speakAwait(toSpeak, urgency)
        } catch (t: Throwable) {
            Log.w(TAG, "speakAwait failed", t)
        }

        pushState { it.copy(
            timestampSec = nowSec(),
            loopStatus = LoopStatus.WAITING,
            rationale = "Waiting for user action on '${step.instructionKey}'.",
        ) }
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
            UserResponseType.BLEEDING_BETTER,
            UserResponseType.RESPONSIVE_CONFIRMED,
            UserResponseType.UNRESPONSIVE_CONFIRMED -> FeedbackDecision(
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

    /**
     * Plan-agnostic critical close-out. These patient-state cues mean the
     * same thing no matter what plan was active a moment ago: escalate and
     * hand off. Always wins over any per-plan resolution.
     */
    private fun criticalResolution(
        response: UserResponseType,
    ): Pair<String, UrgencyLevel>? = when (response) {
        UserResponseType.BREATHING_ABSENT ->
            "Call 911 now. Start chest compressions — press hard and fast in the center of their chest, about two per second. Don't stop until help arrives." to UrgencyLevel.CRITICAL
        UserResponseType.UNRESPONSIVE_CONFIRMED ->
            "Call 911 now. Tell them the person is unresponsive. If they're not breathing, start chest compressions right away." to UrgencyLevel.CRITICAL
        else -> null
    }

    /**
     * Per-plan close-out line + urgency for a resolution utterance. Returns
     * null when [response] is not a clean resolution for [action] — in that
     * case the loop uses its default generic ending. Critical outcomes
     * (unresponsive, not breathing) get a handoff-level close-out.
     */
    private fun resolutionFor(
        action: ActionType,
        response: UserResponseType,
    ): Pair<String, UrgencyLevel>? = when (action) {
        ActionType.CONTROL_BLEEDING -> when (response) {
            UserResponseType.BLEEDING_BETTER, UserResponseType.DONE ->
                "Good. Keep light pressure on the wound and watch for it to start again. Call 911 if it gets worse." to UrgencyLevel.LOW
            else -> null
        }
        ActionType.ASSESS_BREATHING -> when (response) {
            UserResponseType.BREATHING_PRESENT ->
                "Good. Keep watching their chest. Tell me if their breathing changes." to UrgencyLevel.LOW
            UserResponseType.BREATHING_ABSENT ->
                "Call 911 now. Tell them the person isn't breathing. Stay with me." to UrgencyLevel.CRITICAL
            else -> null
        }
        ActionType.ASSESS_RESPONSIVENESS -> when (response) {
            UserResponseType.RESPONSIVE_CONFIRMED, UserResponseType.YES, UserResponseType.DONE ->
                "Good. Stay with them and keep them calm. Tell me if anything changes." to UrgencyLevel.LOW
            UserResponseType.UNRESPONSIVE_CONFIRMED, UserResponseType.NO ->
                "Call 911 now. Tell them the person is unresponsive. I'll keep watching." to UrgencyLevel.CRITICAL
            else -> null
        }
        ActionType.PROTECT_AIRWAY -> when (response) {
            UserResponseType.DONE, UserResponseType.YES ->
                "Good. Keep their airway clear and watch their breathing." to UrgencyLevel.LOW
            else -> null
        }
        ActionType.ENSURE_SCENE_SAFETY -> when (response) {
            UserResponseType.YES, UserResponseType.DONE ->
                "Good. Now focus on the person. Tell me what you see." to UrgencyLevel.MODERATE
            else -> null
        }
        else -> null
    }

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
        // Only prepend "Now. " for explicit URGENT escalation. The old
        // "Careful. " prefix on every READY_WITH_CAUTION step produced
        // stuttering "Careful. Careful." bursts when the plan oscillated
        // and made routine guidance feel alarmist.
        val prefix = if (escalation == EscalationLevel.URGENT) "Now. " else ""
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
        // How long to wait for Phase-2 state emissions to settle before
        // kicking off a spoken cycle. Short enough to still feel snappy,
        // long enough to coalesce a VLM-response burst.
        private const val CYCLE_DEBOUNCE_MS = 600L
        // Minimum time from the start of an utterance during which a
        // state-driven relaunch must yield instead of canceling. Prevents
        // the "Careful. Careful. Careful." stutter from rapid plan churn.
        private const val SPEAK_PROTECT_MS = 2_500L
    }
}

