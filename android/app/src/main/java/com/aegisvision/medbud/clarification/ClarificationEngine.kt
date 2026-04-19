package com.aegisvision.medbud.clarification

import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.decision.PriorityType
import com.aegisvision.medbud.decision.UrgencyLevel
import com.aegisvision.medbud.perception.PerceptionState
import com.aegisvision.medbud.perception.Source
import com.aegisvision.medbud.perception.nowSec

/**
 * Phase 2.2 — clarification / next-best-observation engine.
 *
 * Pure function from `(PerceptionState, DecisionState) -> ClarificationState`.
 * No hidden state between calls, so trivially testable and safe to call
 * inside a StateFlow `map`.
 *
 * Picks ONE best clarification (camera move or short question) and
 * ranks a few others for downstream use. The engine never plans
 * treatment — only what to observe next.
 */
object ClarificationEngine {

    /** Body parts where a plausible bleed site could be visible. */
    private val WOUND_PLAUSIBLE_PARTS = setOf(
        "arm", "leg", "chest", "abdomen", "head", "neck", "hand", "foot", "back",
    )

    // Score below this is considered "noise" and never becomes primary.
    private const val ACTIVE_THRESHOLD = 0.15

    // ---------------------------------------------------------------- evaluate

    fun evaluate(perception: PerceptionState, decision: DecisionState): ClarificationState {
        val now = nowSec()

        // Early-exit: decision is confident enough and has no unmet needs.
        if (shouldBeSilent(decision)) {
            return ClarificationState(
                timestampSec = now,
                primaryClarificationNeed = ClarificationNeed(
                    type = ClarificationType.NO_CLARIFICATION_NEEDED,
                    priority = 0.0,
                    reason = "decision confidence=${fmt(decision.confidence)} with no blockers",
                    blockingPriority = decision.primaryPriority,
                    targetField = null,
                ),
                candidateNeeds = emptyList(),
                recommendedPrompt = PromptInstruction.passive(),
                confidenceGainEstimate = 0.0,
                rationale = "Observation is sufficient; continuing to monitor.",
            )
        }

        val candidates = listOfNotNull(
            locatePatientNeed(perception, decision),
            assessSceneSafetyNeed(perception, decision),
            resolveContradictionNeed(perception, decision),
            locateWoundNeed(perception, decision),
            locateChestNeed(perception, decision),
            confirmBreathingNeed(perception, decision),
            confirmResponsivenessNeed(perception, decision),
            confirmBleedingSourceNeed(perception, decision),
            gatherGeneralInfoNeed(perception, decision),
        ).filter { it.priority >= ACTIVE_THRESHOLD }
         .sortedByDescending { it.priority }

        val primary = candidates.firstOrNull() ?: noopNeed()
        val secondaries = candidates.drop(1).take(3)

        val prompt = selectPrompt(primary)
        val gain = estimateConfidenceGain(primary, decision)
        val rationale = buildRationale(primary, decision)

        return ClarificationState(
            timestampSec = now,
            primaryClarificationNeed = primary,
            candidateNeeds = secondaries,
            recommendedPrompt = prompt,
            confidenceGainEstimate = gain,
            rationale = rationale,
        )
    }

    // ------------------------------------------------------------- need rules

    private fun shouldBeSilent(d: DecisionState): Boolean {
        if (d.primaryPriority == PriorityType.NO_PERSON_DETECTED) return false
        if (d.primaryPriority == PriorityType.SCENE_SAFETY) return false
        if (d.primaryPriority == PriorityType.UNKNOWN_MEDICAL_RISK) return false
        return d.confidence >= 0.70 && d.blockers.isEmpty() && d.missingInfo.isEmpty()
    }

    private fun locatePatientNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        if (p.personVisible.value == "yes") return null
        val certainty = p.personVisible.confidence
        // Only fire if we're reasonably sure there's no person; otherwise
        // the UNKNOWN / GATHER_GENERAL rule covers the "who knows" case.
        if (certainty < 0.30 && d.primaryPriority != PriorityType.NO_PERSON_DETECTED) return null
        return ClarificationNeed(
            type = ClarificationType.LOCATE_PATIENT,
            priority = 0.95,
            reason = "no person currently in frame (person_visible conf=${fmt(certainty)})",
            blockingPriority = PriorityType.NO_PERSON_DETECTED,
            targetField = "person_visible",
        )
    }

    private fun assessSceneSafetyNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val hazardPresent = p.sceneRisk.isNotEmpty()
        val sceneUnknown = p.sceneRisk.isEmpty() &&
                p.personVisible.value == "yes" &&
                p.framesInBuffer >= 2 &&
                d.primaryPriority in setOf(
                    PriorityType.MAJOR_BLEEDING,
                    PriorityType.BREATHING_RISK,
                    PriorityType.AIRWAY_RISK,
                    PriorityType.UNRESPONSIVE_PERSON,
                )
        if (!hazardPresent && !sceneUnknown) return null

        val priority = when {
            hazardPresent && d.urgency == UrgencyLevel.CRITICAL -> 0.90
            hazardPresent -> 0.80
            else -> 0.30  // background check before getting close
        }
        return ClarificationNeed(
            type = ClarificationType.ASSESS_SCENE_SAFETY,
            priority = priority,
            reason = if (hazardPresent) "hazard detected: ${p.sceneRisk.joinToString(", ")}"
                     else "scene context not yet confirmed before closer assessment",
            blockingPriority = PriorityType.SCENE_SAFETY,
            targetField = "scene_risk",
        )
    }

    private fun resolveContradictionNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val contradictions = mutableListOf<String>()

        // 1) Clearly conscious but simultaneously reported not breathing.
        //    A conscious person cannot be persistently apneic.
        if (p.conscious.value == "yes" && p.breathing.value == "none" &&
            p.conscious.confidence > 0.40 && p.breathing.confidence > 0.25) {
            contradictions += "conscious=yes but breathing=none"
        }
        // 2) Heavy bleeding reported, but no plausible wound-bearing body part visible.
        if (p.bleeding.value == "heavy" &&
            p.bodyPartsVisible.none { it in WOUND_PLAUSIBLE_PARTS }) {
            contradictions += "bleeding=heavy but no wound-bearing region visible"
        }
        // 3) Primary priority depends entirely on speech with weak visual support.
        val speechDriven = listOf(p.bleeding, p.breathing, p.conscious)
            .any { it.source == Source.SPEECH && it.visualSupport < 0.25 && it.confidence > 0.20 }
        if (speechDriven && d.urgency in setOf(UrgencyLevel.HIGH, UrgencyLevel.CRITICAL)) {
            contradictions += "high-urgency priority driven by voice; visual support weak"
        }

        if (contradictions.isEmpty()) return null
        val priority = when (d.urgency) {
            UrgencyLevel.CRITICAL -> 0.88
            UrgencyLevel.HIGH -> 0.82
            else -> 0.60
        }
        return ClarificationNeed(
            type = ClarificationType.RESOLVE_SIGNAL_CONTRADICTION,
            priority = priority,
            reason = contradictions.joinToString("; "),
            blockingPriority = d.primaryPriority,
            targetField = null,
        )
    }

    private fun locateWoundNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val b = p.bleeding
        if (b.value != "heavy" && b.value != "minor") return null
        val woundVisible = p.bodyPartsVisible.any { it in WOUND_PLAUSIBLE_PARTS }
        if (woundVisible && b.confidence > 0.50) return null
        val base = if (b.value == "heavy") 0.80 else 0.45
        val bonus = when (d.urgency) {
            UrgencyLevel.CRITICAL -> 0.10
            UrgencyLevel.HIGH -> 0.05
            else -> 0.0
        }
        return ClarificationNeed(
            type = ClarificationType.LOCATE_WOUND,
            priority = base + bonus,
            reason = "bleeding=${b.value} but wound-bearing region not clearly visible",
            blockingPriority = PriorityType.MAJOR_BLEEDING,
            targetField = "body_parts_visible",
        )
    }

    private fun locateChestNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val br = p.breathing
        if (br.value !in setOf("none", "abnormal")) return null
        if ("chest" in p.bodyPartsVisible && br.confidence > 0.40) return null
        val base = 0.80
        val bonus = when (d.urgency) {
            UrgencyLevel.CRITICAL -> 0.12
            UrgencyLevel.HIGH -> 0.05
            else -> 0.0
        }
        return ClarificationNeed(
            type = ClarificationType.LOCATE_CHEST,
            priority = base + bonus,
            reason = "breathing=${br.value} but chest not clearly visible",
            blockingPriority = PriorityType.BREATHING_RISK,
            targetField = "breathing",
        )
    }

    private fun confirmBreathingNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val br = p.breathing
        if (br.value !in setOf("none", "abnormal")) return null
        if (br.source != Source.SPEECH) return null
        if (br.visualSupport >= 0.35) return null
        val priority = 0.72 + if (d.urgency == UrgencyLevel.CRITICAL) 0.08 else 0.0
        return ClarificationNeed(
            type = ClarificationType.CONFIRM_BREATHING,
            priority = priority,
            reason = "breathing concern driven by voice; not visually confirmed (vs=${fmt(br.visualSupport)})",
            blockingPriority = PriorityType.BREATHING_RISK,
            targetField = "breathing",
        )
    }

    private fun confirmResponsivenessNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val c = p.conscious
        if (c.value != "no") return null
        if (c.source != Source.SPEECH) return null
        if (c.visualSupport >= 0.35) return null
        return ClarificationNeed(
            type = ClarificationType.CONFIRM_RESPONSIVENESS,
            priority = 0.62,
            reason = "unresponsiveness driven by voice; not visually confirmed (vs=${fmt(c.visualSupport)})",
            blockingPriority = PriorityType.UNRESPONSIVE_PERSON,
            targetField = "conscious",
        )
    }

    private fun confirmBleedingSourceNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        val b = p.bleeding
        // Fires when bleeding is visible, wound area is visible, but source is
        // uncertain (low confidence or speech-dominant). Distinct from LOCATE_WOUND,
        // which fires when the region itself isn't in frame.
        if (b.value != "heavy" && b.value != "minor") return null
        val woundVisible = p.bodyPartsVisible.any { it in WOUND_PLAUSIBLE_PARTS }
        if (!woundVisible) return null
        val weak = b.source == Source.SPEECH || b.confidence < 0.40
        if (!weak) return null
        return ClarificationNeed(
            type = ClarificationType.CONFIRM_BLEEDING_SOURCE,
            priority = 0.55,
            reason = "wound area visible but bleeding source still ambiguous",
            blockingPriority = PriorityType.MAJOR_BLEEDING,
            targetField = "bleeding",
        )
    }

    private fun gatherGeneralInfoNeed(p: PerceptionState, d: DecisionState): ClarificationNeed? {
        if (p.personVisible.value != "yes") return null
        val unknowns = listOf(p.bleeding.value, p.breathing.value, p.conscious.value)
            .count { it == "unknown" }
        if (unknowns < 2) return null
        // Keep this low-priority on purpose — any concrete need should outrank it.
        val priority = 0.35 - 0.10 * p.globalConfidence
        return ClarificationNeed(
            type = ClarificationType.GATHER_GENERAL_INFORMATION,
            priority = priority.coerceIn(0.20, 0.40),
            reason = "$unknowns of 3 critical fields are still unknown",
            blockingPriority = d.primaryPriority,
            targetField = null,
        )
    }

    private fun noopNeed() = ClarificationNeed(
        type = ClarificationType.NO_CLARIFICATION_NEEDED,
        priority = 0.0,
        reason = "no active clarification",
        blockingPriority = null,
        targetField = null,
    )

    // --------------------------------------------------------- prompt mapping

    private fun selectPrompt(need: ClarificationNeed): PromptInstruction = when (need.type) {
        ClarificationType.LOCATE_PATIENT -> PromptInstruction(
            mode = PromptMode.CAMERA_MOVE, target = "patient",
            promptText = "Show the patient.",
            shortLabel = "locate_patient",
        )
        ClarificationType.ASSESS_SCENE_SAFETY -> PromptInstruction(
            mode = PromptMode.CAMERA_MOVE, target = "scene",
            promptText = "Look around for danger.",
            shortLabel = "assess_scene",
        )
        ClarificationType.LOCATE_WOUND -> PromptInstruction(
            mode = PromptMode.CAMERA_MOVE, target = "wound",
            promptText = "Show where the bleeding is coming from.",
            shortLabel = "locate_wound",
        )
        ClarificationType.LOCATE_CHEST -> PromptInstruction(
            mode = PromptMode.CAMERA_MOVE, target = "chest",
            promptText = "Point the camera at the chest.",
            shortLabel = "locate_chest",
        )
        ClarificationType.CONFIRM_BREATHING -> PromptInstruction(
            mode = PromptMode.ASK_USER, target = "breathing",
            promptText = "Is the chest moving?",
            shortLabel = "confirm_breathing",
        )
        ClarificationType.CONFIRM_RESPONSIVENESS -> PromptInstruction(
            mode = PromptMode.ASK_USER, target = "responsiveness",
            promptText = "Does the person respond when you speak?",
            shortLabel = "confirm_responsiveness",
        )
        ClarificationType.CONFIRM_BLEEDING_SOURCE -> PromptInstruction(
            mode = PromptMode.ASK_USER, target = "bleeding",
            promptText = "Where is the blood coming from?",
            shortLabel = "confirm_bleeding_source",
        )
        ClarificationType.RESOLVE_SIGNAL_CONTRADICTION -> PromptInstruction(
            mode = PromptMode.ASK_USER, target = "contradiction",
            promptText = "Can you describe what you're seeing right now?",
            shortLabel = "resolve_contradiction",
        )
        ClarificationType.GATHER_GENERAL_INFORMATION -> PromptInstruction(
            mode = PromptMode.CAMERA_MOVE, target = "person",
            promptText = "Show the person more clearly.",
            shortLabel = "gather_information",
        )
        ClarificationType.NO_CLARIFICATION_NEEDED -> PromptInstruction.passive()
    }

    // ------------------------------------------------------- confidence gain

    private fun estimateConfidenceGain(need: ClarificationNeed, d: DecisionState): Double {
        if (need.type == ClarificationType.NO_CLARIFICATION_NEEDED) return 0.0

        var gain = 0.30  // baseline reward for any active clarification

        // Bonus if this need unblocks the current primary priority.
        if (need.blockingPriority != null && need.blockingPriority == d.primaryPriority) {
            gain += 0.20
        }

        // Urgency bonus: clarifying a CRITICAL situation has more value.
        gain += when (d.urgency) {
            UrgencyLevel.CRITICAL -> 0.25
            UrgencyLevel.HIGH -> 0.15
            UrgencyLevel.MODERATE -> 0.05
            UrgencyLevel.LOW -> 0.0
        }

        // Larger gain when the decision we have is weak.
        if (d.confidence < 0.40) gain += 0.15

        // Contradictions resolve multiple fields at once → extra value.
        if (need.type == ClarificationType.RESOLVE_SIGNAL_CONTRADICTION) gain += 0.10

        return gain.coerceIn(0.0, 1.0)
    }

    // --------------------------------------------------------------- rationale

    private fun buildRationale(primary: ClarificationNeed, d: DecisionState): String {
        if (primary.type == ClarificationType.NO_CLARIFICATION_NEEDED) {
            return "Observation is sufficient (decision confidence=${fmt(d.confidence)})."
        }
        val base = when (primary.type) {
            ClarificationType.LOCATE_PATIENT ->
                "Cannot triage without a patient in frame."
            ClarificationType.ASSESS_SCENE_SAFETY ->
                "Scene risk must be understood before closer assessment."
            ClarificationType.LOCATE_WOUND ->
                "Bleeding reported but wound region is not currently visible."
            ClarificationType.LOCATE_CHEST ->
                "Breathing concern; chest not currently in view."
            ClarificationType.CONFIRM_BREATHING ->
                "Breathing signal is voice-driven; visual confirmation needed."
            ClarificationType.CONFIRM_RESPONSIVENESS ->
                "Unresponsiveness reported by voice; confirmation needed."
            ClarificationType.CONFIRM_BLEEDING_SOURCE ->
                "Wound area visible but bleeding source not confirmed."
            ClarificationType.RESOLVE_SIGNAL_CONTRADICTION ->
                "Conflicting signals detected: ${primary.reason}."
            ClarificationType.GATHER_GENERAL_INFORMATION ->
                "Multiple critical fields still unknown; a closer look is needed."
            ClarificationType.NO_CLARIFICATION_NEEDED -> ""
        }
        return base
    }

    private fun fmt(v: Double): String = "%.2f".format(v)
}
