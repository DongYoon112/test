package com.aegisvision.medbud.guidance

import com.aegisvision.medbud.perception.nowSec

/**
 * Where the speak-listen-decide loop currently is.
 *
 * The loop runs one step at a time; only one of these is active. Used by
 * the UI + by coordination logic deciding whether to interrupt.
 */
enum class LoopStatus {
    IDLE,       // no current instruction; passive
    SPEAKING,   // TTS playback in progress
    LISTENING,  // mic open, collecting a user response
    DECIDING,   // parsing a response / deciding next action
    WAITING,    // paused (between cycles, or after repeated failures)
    ESCALATED,  // elevated mode — shorter prompts, more directness
    HANDOFF,    // system has directed the user to external help
}

/**
 * Compact categories for a parsed user utterance. Phase 3.2's
 * [ResponseInterpreter] maps every STT transcript to one of these,
 * including [UNCLEAR] for noise and [NO_RESPONSE] for silence.
 */
enum class UserResponseType {
    YES,
    NO,
    DONE,
    CANT_DO,
    DONT_KNOW,
    NO_RESPONSE,
    BREATHING_PRESENT,
    BREATHING_ABSENT,
    BLEEDING_WORSE,
    BLEEDING_BETTER,
    HELP_REQUEST,
    UNCLEAR,
}

/** Step-level outcome after one speak/listen/decide cycle. */
enum class StepCompletionState {
    PENDING,         // just started or mid-cycle
    COMPLETED,       // user confirmed OR perception confirms
    SKIPPED,         // user couldn't; loop moved past
    FAILED,          // repeated failure; loop escalated
    INTERRUPTED,     // plan changed or urgency spiked mid-cycle
}

/**
 * Rising ladder of assertiveness. Bumps when the user can't comply or
 * signals keep deteriorating. Controls how terse and how often we prompt.
 */
enum class EscalationLevel { NONE, ELEVATED, URGENT, HANDOFF }

/** What the loop decided to do with the most recent cycle. Private-ish — exposed for debugging. */
enum class FeedbackAction {
    ADVANCE,           // move to next step
    REPEAT,            // re-speak the current step
    CLARIFY,           // issue a clarification prompt
    ESCALATE,          // bump escalation + re-speak tersely
    HANDOFF,           // trigger external-help handoff path
    CONTINUE_SILENT,   // hold position, keep watching
}

/**
 * What the loop chose, and exactly what it will say next (if anything).
 * This object lives for one decision; the running state is [GuidanceLoopState].
 */
data class FeedbackDecision(
    val action: FeedbackAction,
    val speakText: String?,
    val advanceStep: Boolean,
    val requestClarification: Boolean,
    val interruptCurrentSpeech: Boolean,
    val reason: String,
)

/**
 * Running snapshot of the guidance loop. Emitted via StateFlow so the UI
 * (and Phase 4+ consumers) can observe confirmation flow without polling.
 */
data class GuidanceLoopState(
    val timestampSec: Double,
    val currentInstructionKey: String?,
    val currentStepIndex: Int,
    val loopStatus: LoopStatus,
    val lastUserResponse: UserResponseType,
    val completionState: StepCompletionState,
    val retryCount: Int,
    val escalationLevel: EscalationLevel,
    val rationale: String,
) {
    companion object {
        fun initial() = GuidanceLoopState(
            timestampSec = nowSec(),
            currentInstructionKey = null,
            currentStepIndex = 0,
            loopStatus = LoopStatus.IDLE,
            lastUserResponse = UserResponseType.NO_RESPONSE,
            completionState = StepCompletionState.PENDING,
            retryCount = 0,
            escalationLevel = EscalationLevel.NONE,
            rationale = "Awaiting first instruction.",
        )
    }
}
