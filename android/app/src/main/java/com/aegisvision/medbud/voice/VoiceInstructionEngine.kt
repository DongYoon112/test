package com.aegisvision.medbud.voice

import android.util.Log
import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.action.ActionPlanStatus
import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.perception.PerceptionRepository
import com.aegisvision.medbud.perception.nowSec
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
 * Phase 3.1 voice delivery engine.
 *
 * Observes the three Phase-2 StateFlows and turns them into short spoken
 * instructions via [ElevenLabsTTSManager]. Decides:
 *   * which sentence to speak right now (based on action-plan status)
 *   * whether to repeat, dedup, or interrupt previous speech
 *   * which step in `plannedSteps` is current (mock progression for Phase 3.1)
 *
 * Step progression is manual — Phase 3.2 will automate confirmation.
 */
class VoiceInstructionEngine(private val tts: ElevenLabsTTSManager) {

    /** Last sentence this engine spoke (for UI indicators + dedup). */
    val lastSpoken: StateFlow<SpokenLine?> get() = _lastSpoken
    private val _lastSpoken = MutableStateFlow<SpokenLine?>(null)

    data class SpokenLine(
        val text: String,
        val urgency: UrgencyLevel,
        val timestampSec: Double,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    // State held across emissions for dedup + step progression.
    @Volatile private var lastSpokenText: String? = null
    @Volatile private var lastUrgency: UrgencyLevel = UrgencyLevel.LOW
    @Volatile private var lastPlanIdentity: String = ""
    @Volatile private var currentStepIndex: Int = 0

    // -------------------------------------------------------- lifecycle

    fun start(repo: PerceptionRepository) {
        if (observerJob != null) return
        observerJob = scope.launch {
            combine(
                repo.actionPlanState,
                repo.decisionState,
                repo.clarificationState,
            ) { plan, decision, clarification ->
                Snapshot(plan, decision, clarification)
            }.collect { snap ->
                handle(snap)
            }
        }
    }

    fun stop() {
        observerJob?.cancel()
        observerJob = null
        tts.interrupt()
        scope.cancel()
    }

    /**
     * Advance to the next step. Phase 3.2 will call this on user confirmation;
     * for Phase 3.1 it's exposed so the debug UI or a test hook can step
     * through the plan manually.
     */
    fun advanceStep() {
        currentStepIndex += 1
    }

    fun resetSteps() {
        currentStepIndex = 0
    }

    /**
     * Allow an external driver (Phase 3.2+ guidance loop) to publish what it
     * just attempted to speak. Drives the Voice row in the debug UI so you
     * can distinguish "loop fired but TTS failed" from "loop never fired".
     */
    fun publishSpokenLine(text: String, urgency: UrgencyLevel) {
        _lastSpoken.value = SpokenLine(
            text = text,
            urgency = urgency,
            timestampSec = nowSec(),
        )
    }

    // ---------------------------------------------------------- core

    private data class Snapshot(
        val plan: ActionPlanState,
        val decision: DecisionState,
        val clarification: ClarificationState,
    )

    private suspend fun handle(snap: Snapshot) {
        val plan = snap.plan
        val d = snap.decision
        val c = snap.clarification

        // ---- plan identity check: reset step index when branch changes ----
        val planId = buildString {
            append(plan.primaryAction.name); append('|')
            plan.plannedSteps.joinTo(this, ",") { it.instructionKey }
        }
        val planChanged = planId != lastPlanIdentity
        if (planChanged) {
            lastPlanIdentity = planId
            currentStepIndex = 0
        }

        val urgencyIncreased = d.urgency.ordinal > lastUrgency.ordinal

        // ---- decide what to speak ----
        val toSpeak: String? = when (plan.status) {
            ActionPlanStatus.NOT_READY -> null
            ActionPlanStatus.MONITOR_ONLY -> null

            ActionPlanStatus.CLARIFY_FIRST -> {
                // Speak the Phase 2.2 clarification prompt directly — it is
                // already a short, app-usable sentence.
                c.recommendedPrompt.promptText.ifBlank { null }
            }

            ActionPlanStatus.READY, ActionPlanStatus.READY_WITH_CAUTION -> {
                val step = plan.plannedSteps.getOrNull(currentStepIndex) ?: return
                val phrase = InstructionMapper.phraseFor(step.instructionKey)
                val prefix = if (
                    plan.status == ActionPlanStatus.READY_WITH_CAUTION &&
                    plan.safetyFlags.isNotEmpty()
                ) "Careful. " else ""
                prefix + phrase
            }
        }

        // Remember urgency even when we chose silence — so the next
        // urgency-based interrupt comparison is correct.
        if (toSpeak == null) {
            lastUrgency = d.urgency
            return
        }

        // ---- dedup: only speak when the text changes or urgency climbs ----
        val shouldSpeak = planChanged ||
                          urgencyIncreased ||
                          toSpeak != lastSpokenText
        if (!shouldSpeak) {
            lastUrgency = d.urgency
            return
        }

        // ---- interrupt + speak ----
        try {
            tts.speak(toSpeak, d.urgency)
        } catch (t: Throwable) {
            Log.w(TAG, "TTS speak failed", t)
        }
        lastSpokenText = toSpeak
        lastUrgency = d.urgency
        _lastSpoken.value = SpokenLine(
            text = toSpeak,
            urgency = d.urgency,
            timestampSec = nowSec(),
        )
    }

    companion object { private const val TAG = "VoiceEngine" }
}
