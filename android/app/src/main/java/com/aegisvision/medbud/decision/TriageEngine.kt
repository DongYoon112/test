package com.aegisvision.medbud.decision

import com.aegisvision.medbud.decision.PriorityAssessment.Companion.inactive
import com.aegisvision.medbud.perception.PerceptionState
import com.aegisvision.medbud.perception.Source
import com.aegisvision.medbud.perception.Trend
import com.aegisvision.medbud.perception.nowSec

/**
 * Rule-based triage engine — Phase 2.1.
 *
 * Pure function from [PerceptionState] → [DecisionState]. No state held
 * between calls, so it's cheap to re-run whenever perception changes and
 * trivially testable.
 *
 * Philosophy:
 *   * explainability first — every decision has a [rationale]
 *   * caution — speech-only signals down-weight both score and urgency
 *   * priority ranking, NOT treatment planning
 */
object TriageEngine {

    // -------------------------------------------------------------- constants

    /** Scene risks we treat as immediately dangerous to the wearer too. */
    private val SCENE_SEV_HIGH = setOf("fire", "electrical", "weapon")
    private val SCENE_SEV_MED = setOf("smoke", "traffic", "water")
    private val SCENE_SEV_LOW = setOf("fall", "cold", "heat", "crowd")

    private val BODY_PARTS_WOUND_PLAUSIBLE = setOf(
        "arm", "leg", "chest", "abdomen", "head", "neck", "hand", "foot", "back",
    )

    private const val ACTIVE_THRESHOLD = 0.10
    private const val AMBIGUITY_RATIO = 0.75  // secondary within 75% of primary → ambiguous

    // --------------------------------------------------------------- public API

    fun evaluate(state: PerceptionState): DecisionState {
        val now = nowSec()

        // Hard override: if perception is confident there's no person, we
        // cannot triage medical priorities; lock to NO_PERSON_DETECTED.
        //
        // EXCEPT: a close-up of a bleeding hand fills the frame and the VLM
        // reports person_visible=no even though a limb is clearly on screen.
        // We keep the previous state in those cases — a visible body part,
        // an active bleeding/breathing/consciousness signal, or a recent
        // spoken update all count as "person is still with us".
        val hasBodyPart = state.bodyPartsVisible.isNotEmpty()
        val hasAcuteSignal =
            (state.bleeding.value !in setOf("unknown", "none") &&
                state.bleeding.confidence >= 0.25) ||
            (state.breathing.value != "unknown" &&
                state.breathing.value != "normal" &&
                state.breathing.confidence >= 0.25) ||
            (state.conscious.value == "no" &&
                state.conscious.confidence >= 0.25)

        if (state.personVisible.value == "no" &&
            state.personVisible.confidence >= 0.4 &&
            !hasBodyPart &&
            !hasAcuteSignal) {
            return DecisionState(
                timestampSec = now,
                primaryPriority = PriorityType.NO_PERSON_DETECTED,
                secondaryPriorities = emptyList(),
                urgency = UrgencyLevel.LOW,
                confidence = state.personVisible.confidence,
                blockers = listOf("no person currently in frame"),
                missingInfo = listOf("patient location"),
                nextFocus = "locate_patient",
                rationale = "Camera does not currently show a person.",
            )
        }

        val assessments = listOf(
            scoreSceneSafety(state),
            scoreMajorBleeding(state),
            scoreBreathingRisk(state),
            scoreAirwayRisk(state),
            scoreUnresponsive(state),
            scoreUnknownRisk(state),
        ).sortedByDescending { it.score }

        val active = assessments.filter { it.score > ACTIVE_THRESHOLD }
        val primary = active.firstOrNull() ?: monitorOnly(state)

        val secondaryTypes = active.drop(1).take(3).map { it.type }
        val urgency = computeUrgency(primary, state)
        val confidence = computeConfidence(primary, active, state)

        val combinedBlockers = (primary.blockers + generalBlockers(state)).distinct()
        val combinedMissing = (primary.missingInfo + generalMissing(state)).distinct()

        return DecisionState(
            timestampSec = now,
            primaryPriority = primary.type,
            secondaryPriorities = secondaryTypes,
            urgency = urgency,
            confidence = confidence,
            blockers = combinedBlockers,
            missingInfo = combinedMissing,
            nextFocus = primary.nextFocus ?: "continue_monitoring",
            rationale = primary.reason,
        )
    }

    // ------------------------------------------------------------- per-priority

    private fun scoreSceneSafety(s: PerceptionState): PriorityAssessment {
        val hit = s.sceneRisk
        if (hit.isEmpty()) {
            return inactive(PriorityType.SCENE_SAFETY, "no scene risk detected")
        }
        val severity = when {
            hit.any { it in SCENE_SEV_HIGH } -> 0.90
            hit.any { it in SCENE_SEV_MED } -> 0.65
            hit.any { it in SCENE_SEV_LOW } -> 0.35
            else -> 0.0
        }
        if (severity == 0.0) return inactive(PriorityType.SCENE_SAFETY, "no recognised hazard")

        // scene_risk is a list field — we don't have per-field confidence,
        // so we use the perception global confidence as a proxy (floored so
        // an obvious hazard isn't totally ignored during warm-up).
        val confProxy = s.globalConfidence.coerceIn(0.30, 1.0)
        val score = severity * confProxy

        return PriorityAssessment(
            type = PriorityType.SCENE_SAFETY,
            score = score,
            confidence = confProxy,
            blockers = emptyList(),
            missingInfo = emptyList(),
            nextFocus = "assess_scene_safety",
            reason = "Scene shows ${hit.joinToString(", ")}; environmental risk first.",
        )
    }

    private fun scoreMajorBleeding(s: PerceptionState): PriorityAssessment {
        val b = s.bleeding
        if (b.value == "unknown" || b.value == "none") {
            val blk = if (b.value == "unknown") listOf("bleeding status unclear") else emptyList()
            return inactive(PriorityType.MAJOR_BLEEDING, "no bleeding signal", blk)
        }

        val base = when (b.value) { "heavy" -> 0.85; "minor" -> 0.45; else -> 0.0 }
        var score = base * (0.3 + 0.7 * b.confidence)
        when (b.trend) {
            Trend.INCREASING -> score += 0.10
            Trend.DECREASING -> score -= 0.05
            else -> {}
        }
        if (b.source == Source.SPEECH) score *= 0.70

        val blockers = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (b.visualSupport < 0.40) blockers += "bleeding partly reliant on voice report"
        val woundVisible = s.bodyPartsVisible.any { it in BODY_PARTS_WOUND_PLAUSIBLE }
        if (!woundVisible) missing += "wound location not identified"

        val focus = if (!woundVisible) "locate_wound" else "confirm_bleeding_source"

        val trendWord = when (b.trend) {
            Trend.INCREASING -> " with increasing trend"
            Trend.DECREASING -> " with improving trend"
            else -> ""
        }
        val label = b.value.replaceFirstChar { it.uppercase() }
        return PriorityAssessment(
            type = PriorityType.MAJOR_BLEEDING,
            score = score,
            confidence = b.confidence,
            blockers = blockers,
            missingInfo = missing,
            nextFocus = focus,
            reason = "$label bleeding$trendWord (source=${b.source.name.lowercase()}, conf=${fmt(b.confidence)}).",
        )
    }

    private fun scoreBreathingRisk(s: PerceptionState): PriorityAssessment {
        val br = s.breathing
        if (br.value != "none") return inactive(PriorityType.BREATHING_RISK, "breathing not reported as absent")

        var score = 0.95 * (0.3 + 0.7 * br.confidence)
        if (br.trend == Trend.INCREASING) score += 0.03

        val blockers = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var reliability = 1.0
        if (br.source == Source.SPEECH && br.visualSupport < 0.30) {
            blockers += "breathing absence reported by voice; not visually confirmed"
            reliability = 0.55
            score *= 0.70
        }
        if ("chest" !in s.bodyPartsVisible) missing += "chest not clearly visible"

        val focus = if ("chest" !in s.bodyPartsVisible) "locate_chest" else "confirm_breathing"
        return PriorityAssessment(
            type = PriorityType.BREATHING_RISK,
            score = score,
            confidence = br.confidence * reliability,
            blockers = blockers,
            missingInfo = missing,
            nextFocus = focus,
            reason = "Breathing reported 'none' (source=${br.source.name.lowercase()}, vs=${fmt(br.visualSupport)}).",
        )
    }

    private fun scoreAirwayRisk(s: PerceptionState): PriorityAssessment {
        val br = s.breathing
        if (br.value != "abnormal") return inactive(PriorityType.AIRWAY_RISK, "breathing not reported abnormal")

        var score = 0.75 * (0.3 + 0.7 * br.confidence)
        if (br.trend == Trend.INCREASING) score += 0.05
        if (br.source == Source.SPEECH && br.visualSupport < 0.30) score *= 0.75

        val blockers = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (br.source == Source.SPEECH && br.visualSupport < 0.30) {
            blockers += "abnormal breathing reported by voice; weak visual support"
        }
        val airwayArea = setOf("face", "neck", "chest")
        if (s.bodyPartsVisible.none { it in airwayArea }) missing += "airway/chest area not visible"

        return PriorityAssessment(
            type = PriorityType.AIRWAY_RISK,
            score = score,
            confidence = br.confidence,
            blockers = blockers,
            missingInfo = missing,
            nextFocus = "confirm_airway",
            reason = "Breathing appears abnormal (source=${br.source.name.lowercase()}).",
        )
    }

    private fun scoreUnresponsive(s: PerceptionState): PriorityAssessment {
        val c = s.conscious
        if (c.value != "no") return inactive(PriorityType.UNRESPONSIVE_PERSON, "consciousness not reported absent")

        var score = 0.70 * (0.3 + 0.7 * c.confidence)
        if (c.trend == Trend.INCREASING) score += 0.10        // deteriorating
        if (c.durationSec > 10.0) score += 0.05               // persistent
        if (c.source == Source.SPEECH && c.visualSupport < 0.30) score *= 0.70

        val blockers = mutableListOf<String>()
        if (c.source == Source.SPEECH && c.visualSupport < 0.30) {
            blockers += "unresponsiveness reported by voice; weak visual support"
        }
        return PriorityAssessment(
            type = PriorityType.UNRESPONSIVE_PERSON,
            score = score,
            confidence = c.confidence,
            blockers = blockers,
            missingInfo = emptyList(),
            nextFocus = "confirm_responsiveness",
            reason = "Person appears unresponsive for ${fmt(c.durationSec)}s " +
                    "(source=${c.source.name.lowercase()}).",
        )
    }

    private fun scoreUnknownRisk(s: PerceptionState): PriorityAssessment {
        if (s.personVisible.value != "yes") {
            return inactive(PriorityType.UNKNOWN_MEDICAL_RISK, "person not visible")
        }
        val unknowns = listOf(s.bleeding.value, s.breathing.value, s.conscious.value)
            .count { it == "unknown" }
        if (unknowns < 2 && s.globalConfidence >= 0.30) {
            return inactive(PriorityType.UNKNOWN_MEDICAL_RISK, "sufficient signals present")
        }

        // Score ranges ~0.20–0.40 — intentionally below real findings so it
        // only wins when nothing concrete is active.
        val score = (0.40 - 0.20 * s.globalConfidence).coerceIn(0.15, 0.40)

        val missing = buildList {
            if (s.bleeding.value == "unknown") add("bleeding status")
            if (s.breathing.value == "unknown") add("breathing status")
            if (s.conscious.value == "unknown") add("consciousness status")
        }
        return PriorityAssessment(
            type = PriorityType.UNKNOWN_MEDICAL_RISK,
            score = score,
            confidence = s.globalConfidence,
            blockers = listOf("$unknowns critical field(s) unknown"),
            missingInfo = missing,
            nextFocus = "gather_information",
            reason = "Person visible but $unknowns of 3 critical fields are unknown.",
        )
    }

    private fun monitorOnly(s: PerceptionState): PriorityAssessment =
        PriorityAssessment(
            type = PriorityType.MONITOR_ONLY,
            score = 0.10,
            confidence = s.globalConfidence,
            blockers = emptyList(),
            missingInfo = emptyList(),
            nextFocus = "continue_monitoring",
            reason = "No acute signals detected; maintaining observation.",
        )

    // ------------------------------------------------------------- urgency

    private fun computeUrgency(primary: PriorityAssessment, s: PerceptionState): UrgencyLevel {
        val base = when (primary.type) {
            PriorityType.NO_PERSON_DETECTED -> UrgencyLevel.LOW
            PriorityType.MONITOR_ONLY -> UrgencyLevel.LOW
            PriorityType.UNKNOWN_MEDICAL_RISK -> UrgencyLevel.MODERATE

            PriorityType.SCENE_SAFETY ->
                if (s.sceneRisk.any { it in SCENE_SEV_HIGH }) UrgencyLevel.CRITICAL
                else UrgencyLevel.HIGH

            PriorityType.MAJOR_BLEEDING -> when {
                s.bleeding.value == "heavy" && s.bleeding.trend == Trend.INCREASING -> UrgencyLevel.CRITICAL
                s.bleeding.value == "heavy" -> UrgencyLevel.HIGH
                else -> UrgencyLevel.MODERATE
            }

            PriorityType.BREATHING_RISK ->
                if (primary.confidence >= 0.40) UrgencyLevel.CRITICAL else UrgencyLevel.HIGH

            PriorityType.AIRWAY_RISK ->
                if (s.breathing.trend == Trend.INCREASING) UrgencyLevel.CRITICAL else UrgencyLevel.HIGH

            PriorityType.UNRESPONSIVE_PERSON ->
                if (s.conscious.trend == Trend.INCREASING) UrgencyLevel.CRITICAL else UrgencyLevel.HIGH
        }

        // Weak signal → step down one level (but never below LOW).
        if (primary.confidence < 0.30 && base != UrgencyLevel.LOW) {
            return when (base) {
                UrgencyLevel.CRITICAL -> UrgencyLevel.HIGH
                UrgencyLevel.HIGH -> UrgencyLevel.MODERATE
                UrgencyLevel.MODERATE -> UrgencyLevel.LOW
                UrgencyLevel.LOW -> UrgencyLevel.LOW
            }
        }
        return base
    }

    // ------------------------------------------------------------- confidence

    private fun computeConfidence(
        primary: PriorityAssessment,
        active: List<PriorityAssessment>,
        s: PerceptionState,
    ): Double {
        if (primary.type == PriorityType.MONITOR_ONLY) return s.globalConfidence.coerceAtMost(0.90)

        var conf = primary.score.coerceAtMost(0.95)

        // Ambiguity: a close runner-up means we're not that sure which wins.
        val secondary = active.getOrNull(1)
        if (secondary != null && primary.score > 0 && secondary.score / primary.score >= AMBIGUITY_RATIO) {
            conf *= 0.80
        }

        // Speech-only BREATHING_RISK is a known weakness → extra penalty.
        if (primary.type == PriorityType.BREATHING_RISK && s.breathing.source == Source.SPEECH) {
            conf *= 0.70
        }

        // Too many unknowns overall → down-weight.
        val unknowns = listOf(s.bleeding.value, s.breathing.value, s.conscious.value)
            .count { it == "unknown" }
        if (unknowns >= 2) conf *= 0.85

        return conf.coerceIn(0.0, 0.95)
    }

    // --------------------------------------------------------- auxiliary info

    private fun generalBlockers(s: PerceptionState): List<String> = buildList {
        if (s.globalConfidence < 0.20) add("overall observation confidence is low")
        if (s.framesInBuffer < 3) add("fewer than 3 frames observed so far")
    }

    private fun generalMissing(s: PerceptionState): List<String> = buildList {
        if (s.sceneRisk.isEmpty() && s.personVisible.value == "yes") add("surrounding scene context")
    }

    private fun fmt(v: Double): String = "%.2f".format(v)
}
