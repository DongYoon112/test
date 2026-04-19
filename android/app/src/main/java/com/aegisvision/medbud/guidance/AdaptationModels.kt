package com.aegisvision.medbud.guidance

import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.perception.nowSec
import com.aegisvision.medbud.voice.InstructionLevel

/**
 * Phase 3.3 — overall regime the guidance loop is running in.
 *
 *   NORMAL            — everything's fine; speak fully, listen patiently.
 *   SIMPLIFIED        — user is struggling OR recovering; shorter phrases.
 *   URGENT            — elevated escalation or HIGH urgency; terse + fast.
 *   CRITICAL_OVERRIDE — decision.urgency == CRITICAL; minimal phrases,
 *                       fastest pacing, overrides user performance signals.
 */
enum class GuidanceMode { NORMAL, SIMPLIFIED, URGENT, CRITICAL_OVERRIDE }

/** Speed + listen-window bundle. Mapped 1:1 from [GuidanceMode]. */
enum class PacingLevel { NORMAL, FAST, FASTEST }

/**
 * Live adaptation snapshot. Emitted via StateFlow so the UI and downstream
 * consumers can visualise how the system is adjusting.
 *
 *   userReliabilityScore 0.0–1.0 — asymmetric EMA; drops fast, recovers slow
 *   responseSpeed        0.0–1.0 — rough proxy for how quickly the user replies
 *   failureRate          0.0–1.0 — rolling-window fraction of REPEAT/ESCALATE
 */
data class AdaptationState(
    val userReliabilityScore: Double,
    val responseSpeed: Double,
    val failureRate: Double,
    val currentMode: GuidanceMode,
    val instructionComplexity: InstructionLevel,
    val pacing: PacingLevel,
    val timestampSec: Double = nowSec(),
) {
    companion object {
        fun initial() = AdaptationState(
            userReliabilityScore = 0.7,      // benefit of the doubt
            responseSpeed = 0.5,
            failureRate = 0.0,
            currentMode = GuidanceMode.NORMAL,
            instructionComplexity = InstructionLevel.FULL,
            pacing = PacingLevel.NORMAL,
        )
    }
}

/**
 * One cycle's result, fed into the adaptation engine. [success] == true
 * iff the cycle ended with ADVANCE. [speedScore] in [0, 1]: 1.0 means a
 * near-immediate reply, 0.0 means the listen window timed out empty.
 */
data class CycleOutcome(
    val success: Boolean,
    val speedScore: Double,
    val urgency: UrgencyLevel,
    val escalation: EscalationLevel,
    val retryCount: Int,
)
