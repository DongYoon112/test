package com.aegisvision.medbud.guidance

import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.voice.InstructionLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.3 — adaptive guidance engine.
 *
 * Tracks user performance across cycles and derives a [GuidanceMode] that
 * the [GuidanceLoopEngine] consults when:
 *   * picking the phrase variant ([InstructionLevel])
 *   * setting the listen-window duration
 *   * choosing TTS urgency
 *
 * Design: **adapt quickly, recover slowly.**
 *   * Reliability EMA is asymmetric — a single failure cuts it in half,
 *     a single success moves it only ~10% toward 1.0.
 *   * Mode can tighten on any single trigger; relaxing from SIMPLIFIED
 *     back to NORMAL requires ≥2 consecutive successes *and* reliability ≥ 0.6.
 *   * CRITICAL urgency from the decision engine overrides everything else
 *     (it's a patient-state signal, not a user-performance one).
 */
class AdaptationEngine {

    val state: StateFlow<AdaptationState> get() = _state
    private val _state = MutableStateFlow(AdaptationState.initial())

    // Asymmetric EMA for reliability.
    @Volatile private var reliability: Double = 0.7
    @Volatile private var responseSpeed: Double = 0.5
    @Volatile private var consecutiveSuccesses: Int = 0

    // Rolling failure-rate window.
    private val recentOutcomes = ArrayDeque<Boolean>()  // true = success

    // -------------------------------------------------- public API

    /** Record the result of a just-completed cycle. */
    fun recordCycle(outcome: CycleOutcome) {
        // Asymmetric EMA — fast down, slow up.
        reliability = if (outcome.success) {
            ALPHA_UP * 1.0 + (1.0 - ALPHA_UP) * reliability
        } else {
            ALPHA_DOWN * 0.0 + (1.0 - ALPHA_DOWN) * reliability
        }
        consecutiveSuccesses = if (outcome.success) consecutiveSuccesses + 1 else 0
        responseSpeed = 0.3 * outcome.speedScore + 0.7 * responseSpeed

        recentOutcomes.addLast(outcome.success)
        while (recentOutcomes.size > WINDOW_SIZE) recentOutcomes.removeFirst()

        recompute(outcome.urgency, outcome.escalation, outcome.retryCount)
    }

    /**
     * Pre-cycle recompute triggered by the loop before it decides what to
     * say. Lets mode reflect the latest urgency/escalation *before* the
     * next instruction is chosen.
     */
    fun reactToContext(
        urgency: UrgencyLevel,
        escalation: EscalationLevel,
        retryCount: Int,
    ) {
        recompute(urgency, escalation, retryCount)
    }

    /** Listen window in milliseconds for the current mode. */
    fun listenWindowMs(): Long = when (_state.value.currentMode) {
        GuidanceMode.NORMAL -> 5_000L
        GuidanceMode.SIMPLIFIED -> 4_000L
        GuidanceMode.URGENT -> 3_000L
        GuidanceMode.CRITICAL_OVERRIDE -> 2_000L
    }

    /**
     * Effective TTS urgency. Folds base decision urgency with mode —
     * the loop can still get louder if the *patient* state forces it,
     * even when the *user* is performing well.
     */
    fun effectiveUrgency(base: UrgencyLevel): UrgencyLevel = when (_state.value.currentMode) {
        GuidanceMode.NORMAL -> base
        GuidanceMode.SIMPLIFIED -> maxOf(base, UrgencyLevel.MODERATE)
        GuidanceMode.URGENT -> maxOf(base, UrgencyLevel.HIGH)
        GuidanceMode.CRITICAL_OVERRIDE -> UrgencyLevel.CRITICAL
    }

    // -------------------------------------------------- internals

    private fun recompute(
        urgency: UrgencyLevel,
        escalation: EscalationLevel,
        retryCount: Int,
    ) {
        val failureRate = if (recentOutcomes.isEmpty()) 0.0
            else recentOutcomes.count { !it }.toDouble() / recentOutcomes.size

        val mode = pickMode(urgency, escalation, retryCount, failureRate)
        val level = pickLevel(mode, failureRate)
        val pacing = pickPacing(mode)

        _state.value = AdaptationState(
            userReliabilityScore = reliability,
            responseSpeed = responseSpeed,
            failureRate = failureRate,
            currentMode = mode,
            instructionComplexity = level,
            pacing = pacing,
        )
    }

    private fun pickMode(
        urgency: UrgencyLevel,
        escalation: EscalationLevel,
        retryCount: Int,
        failureRate: Double,
    ): GuidanceMode {
        // Patient-state override — always takes precedence.
        if (urgency == UrgencyLevel.CRITICAL) return GuidanceMode.CRITICAL_OVERRIDE

        // Severe escalation → URGENT regardless of user performance.
        if (escalation == EscalationLevel.URGENT || escalation == EscalationLevel.HANDOFF) {
            return GuidanceMode.URGENT
        }

        // Only drop out of NORMAL when we have real evidence of trouble —
        // full instructions are better for a user still learning the flow.
        val trigger = retryCount > 1 ||
            failureRate > 0.7 ||
            reliability < 0.3 ||
            escalation == EscalationLevel.ELEVATED

        if (trigger) return GuidanceMode.SIMPLIFIED

        // Slow recovery from SIMPLIFIED — need both high reliability AND a
        // streak of successful cycles to relax.
        val current = _state.value.currentMode
        if (current == GuidanceMode.SIMPLIFIED) {
            val recovered = reliability >= RECOVERY_RELIABILITY &&
                consecutiveSuccesses >= RECOVERY_STREAK
            return if (recovered) GuidanceMode.NORMAL else GuidanceMode.SIMPLIFIED
        }
        return GuidanceMode.NORMAL
    }

    private fun pickLevel(mode: GuidanceMode, failureRate: Double): InstructionLevel =
        when (mode) {
            GuidanceMode.NORMAL -> InstructionLevel.FULL
            // SIMPLIFIED still gets FULL phrases — we just change pacing /
            // urgency / TTS settings, not the wording. This keeps the demo
            // intelligible instead of degrading to "Find." / "Move.".
            GuidanceMode.SIMPLIFIED -> InstructionLevel.FULL
            GuidanceMode.URGENT ->
                if (failureRate > 0.7) InstructionLevel.MINIMAL else InstructionLevel.SHORT
            GuidanceMode.CRITICAL_OVERRIDE -> InstructionLevel.MINIMAL
        }

    private fun pickPacing(mode: GuidanceMode): PacingLevel = when (mode) {
        GuidanceMode.NORMAL, GuidanceMode.SIMPLIFIED -> PacingLevel.NORMAL
        GuidanceMode.URGENT -> PacingLevel.FAST
        GuidanceMode.CRITICAL_OVERRIDE -> PacingLevel.FASTEST
    }

    companion object {
        // Tuning knobs — kept small and named for easy adjustment later.
        private const val ALPHA_DOWN = 0.5   // hefty drop on failure
        private const val ALPHA_UP = 0.10    // cautious climb on success
        private const val WINDOW_SIZE = 6
        private const val RECOVERY_RELIABILITY = 0.60
        private const val RECOVERY_STREAK = 2
    }
}
