package com.aegisvision.medbud.perception

import com.aegisvision.medbud.perception.TemporalStateTracker.Companion.FIELD_BLEEDING
import com.aegisvision.medbud.perception.TemporalStateTracker.Companion.FIELD_BREATHING
import com.aegisvision.medbud.perception.TemporalStateTracker.Companion.FIELD_CONSCIOUS

/**
 * Ported from Python `SpeechContext` + fusion rules.
 *
 * A rolling window of recent [SpeechEvent]s. Before each tracker snapshot,
 * the pipeline calls [apply] to turn the accumulated transcript into
 * small vote boosts — speech *nudges* the tracker, it does not override it.
 *
 * Rule design deliberately conservative:
 *   - high-weight (≥2.0) only for clear, unambiguous phrasing
 *   - the strong-visual override in the tracker still wins in a tie-breaker
 *   - scene risks get their own list boost path
 */
class FusionEngine(private val windowSeconds: Double = 30.0) {

    private val utterances = ArrayDeque<SpeechEvent>()

    fun addTranscript(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        utterances.addLast(SpeechEvent(cleaned))
        prune()
    }

    fun recentText(): String {
        prune()
        return utterances.joinToString(" ") { it.text }
    }

    /**
     * Push any matching boosts into the tracker.
     *
     * @param applyBoost scalar field boost: `(field, label, weight)`
     * @param applyScene list (scene_risk) boost: `(risk, weight)`
     */
    fun apply(
        applyBoost: (field: String, label: String, weight: Double) -> Unit,
        applyScene: (risk: String, weight: Double) -> Unit,
    ) {
        prune()
        if (utterances.isEmpty()) return
        val text = utterances.joinToString(" ") { it.text }

        for (rule in SCALAR_RULES) {
            if (rule.pattern.containsMatchIn(text)) {
                applyBoost(rule.field, rule.label, rule.weight)
            }
        }
        for (rule in SCENE_RULES) {
            if (rule.pattern.containsMatchIn(text)) {
                applyScene(rule.risk, rule.weight)
            }
        }
    }

    private fun prune() {
        val cutoff = nowSec() - windowSeconds
        while (utterances.isNotEmpty() && utterances.first().timestampSec < cutoff) {
            utterances.removeFirst()
        }
    }

    // ---- rules -----------------------------------------------------------

    private data class ScalarRule(
        val pattern: Regex,
        val field: String,
        val label: String,
        val weight: Double,
    )

    private data class SceneRule(
        val pattern: Regex,
        val risk: String,
        val weight: Double,
    )

    companion object {
        private val CASE_I = setOf(RegexOption.IGNORE_CASE)

        private val SCALAR_RULES = listOf(
            // Breathing
            ScalarRule(Regex("""\b(not\s+breathing|no\s+breath|stopped\s+breathing)\b""", CASE_I),
                FIELD_BREATHING, "none", 2.0),
            ScalarRule(Regex("""\b(can'?t\s+breathe|choking|gasping|wheezing)\b""", CASE_I),
                FIELD_BREATHING, "abnormal", 1.5),
            ScalarRule(Regex("""\b(breathing\s+normally|breathing\s+fine)\b""", CASE_I),
                FIELD_BREATHING, "normal", 1.0),

            // Consciousness
            ScalarRule(Regex("""\b(unconscious|passed\s+out|knocked\s+out|out\s+cold|unresponsive)\b""", CASE_I),
                FIELD_CONSCIOUS, "no", 2.0),
            ScalarRule(Regex("""\b(he'?s\s+awake|she'?s\s+awake|responsive|alert|talking)\b""", CASE_I),
                FIELD_CONSCIOUS, "yes", 1.5),

            // Bleeding
            ScalarRule(Regex("""\b(bleeding\s+(heavily|badly|a\s+lot)|lots?\s+of\s+blood|pouring\s+blood|hemorrhag)""", CASE_I),
                FIELD_BLEEDING, "heavy", 2.0),
            ScalarRule(Regex("""\b(a\s+little\s+blood|small\s+cut|minor\s+bleed)\b""", CASE_I),
                FIELD_BLEEDING, "minor", 1.0),
            ScalarRule(Regex("""\b(no\s+blood|not\s+bleeding)\b""", CASE_I),
                FIELD_BLEEDING, "none", 1.0),
        )

        private val SCENE_RULES = listOf(
            SceneRule(Regex("""\b(fire|flames?|burning)\b""", CASE_I), "fire", 2.0),
            SceneRule(Regex("""\bsmoke\b""", CASE_I), "smoke", 1.5),
            SceneRule(Regex("""\b(traffic|road|highway|cars?\s+coming)\b""", CASE_I), "traffic", 1.5),
            SceneRule(Regex("""\b(water|drowning|pool|river)\b""", CASE_I), "water", 1.5),
            SceneRule(Regex("""\b(electrical|live\s+wire|shocked)\b""", CASE_I), "electrical", 1.5),
            SceneRule(Regex("""\b(gun|knife|weapon)\b""", CASE_I), "weapon", 2.0),
            SceneRule(Regex("""\b(fell|fallen|dropped)\b""", CASE_I), "fall", 1.0),
        )
    }
}
