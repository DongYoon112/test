package com.aegisvision.medbud.perception

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Temporal state tracker — the port of the Python `StateTracker`.
 *
 * Maintains, per medical field:
 *   • recency-weighted + confidence-weighted vote over recent visual frames
 *   • optional speech boosts (from [FusionEngine])
 *   • "strong visual override" — speech cannot flip a confident visual signal
 *   • volatility penalty on confidence
 *   • maturity ramp so a single frame never reads as high-confidence
 *   • trend detection via severity-weighted first/second half comparison
 *   • duration tracking via a per-field value-entered-at timestamp
 *
 * The tracker owns its [ObservationBuffer]; callers push frames with
 * [addFrame] and optional speech-boosts before calling [snapshot].
 */
class TemporalStateTracker(
    bufferCapacity: Int = ObservationBuffer.DEFAULT_CAPACITY,
    private val recencyHalfLifeSec: Double = 6.0,
    private val stableRunFrames: Int = 3,
    private val maturityFullAt: Int = 4,
    private val maxFrameAgeSec: Double = 20.0,
    private val strongVisualWeight: Double = 1.5,
) {
    private val buffer = ObservationBuffer(bufferCapacity)

    // Ephemeral boosts applied by the fusion engine before the next snapshot.
    private val speechBoosts: MutableMap<String, MutableMap<String, Double>> = mutableMapOf()
    private val sceneBoosts: MutableMap<String, Double> = mutableMapOf()

    // Per-field history: value and the wall-clock it was entered at.
    private data class History(val value: String, val enteredAt: Double)
    private val history: MutableMap<String, History> = mutableMapOf()

    // ---- ingestion --------------------------------------------------------

    fun addFrame(frame: ObservationFrame) {
        buffer.add(frame)
    }

    fun applySpeechBoost(field: String, label: String, weight: Double) {
        val inner = speechBoosts.getOrPut(field) { mutableMapOf() }
        inner[label] = (inner[label] ?: 0.0) + weight
    }

    fun applySceneBoost(risk: String, weight: Double) {
        sceneBoosts[risk] = (sceneBoosts[risk] ?: 0.0) + weight
    }

    fun size(): Int = buffer.size()
    fun queryLastN(n: Int): List<ObservationFrame> = buffer.lastN(n)
    fun queryLastSeconds(sec: Double): List<ObservationFrame> = buffer.lastSeconds(sec)

    // ---- snapshot ---------------------------------------------------------

    fun snapshot(): Snapshot {
        buffer.dropOlderThan(maxFrameAgeSec)
        val now = nowSec()

        if (buffer.isEmpty()) {
            speechBoosts.clear(); sceneBoosts.clear()
            return Snapshot(
                bleeding = FieldState.unknown(),
                breathing = FieldState.unknown(),
                conscious = FieldState.unknown(),
                personVisible = FieldState.noPerson(),
                bodyPartsVisible = emptyList(),
                sceneRisk = emptyList(),
                globalConfidence = 0.0,
                framesInBuffer = 0,
            )
        }

        val frames = buffer.snapshot()

        val bleeding = computeFieldState(frames, FIELD_BLEEDING, now)
        val breathing = computeFieldState(frames, FIELD_BREATHING, now)
        val conscious = computeFieldState(frames, FIELD_CONSCIOUS, now)
        val personVisible = computeFieldState(frames, FIELD_PERSON_VISIBLE, now)

        val bodyParts = computeList(frames, FIELD_BODY_PARTS, now, Vocab.BODY_PARTS, boosts = null)
        var sceneRisk = computeList(frames, FIELD_SCENE_RISK, now, Vocab.SCENE_RISKS, boosts = sceneBoosts)
        if ("none" in sceneRisk && sceneRisk.size > 1) {
            sceneRisk = sceneRisk.filter { it != "none" }
        }

        val globalConf = (bleeding.confidence + breathing.confidence + conscious.confidence) / 3.0

        speechBoosts.clear()
        sceneBoosts.clear()

        return Snapshot(
            bleeding = bleeding,
            breathing = breathing,
            conscious = conscious,
            personVisible = personVisible,
            bodyPartsVisible = bodyParts,
            sceneRisk = sceneRisk,
            globalConfidence = globalConf,
            framesInBuffer = frames.size,
        )
    }

    // ---- per-field --------------------------------------------------------

    private fun computeFieldState(
        frames: List<ObservationFrame>,
        field: String,
        now: Double,
    ): FieldState {
        val visualScores = HashMap<String, Double>()
        val confidences = ArrayList<Double>(frames.size)
        for (f in frames) {
            val label = valueFor(f, field)
            val w = recency(f.timestampSec, now) * max(0.05, f.confidence)
            visualScores[label] = (visualScores[label] ?: 0.0) + w
            confidences += f.confidence
        }

        val speechScores: Map<String, Double> = speechBoosts[field]?.toMap() ?: emptyMap()

        val visualWinner = argmaxWithTiebreak(visualScores, field) ?: "unknown"
        val speechWinner = if (speechScores.isEmpty()) null
                           else argmaxWithTiebreak(speechScores, field)

        val combined = HashMap<String, Double>(visualScores)
        for ((k, v) in speechScores) combined[k] = (combined[k] ?: 0.0) + v
        val combinedWinner = argmaxWithTiebreak(combined, field) ?: "unknown"

        // Strong-visual override: speech cannot flip a confident visual winner.
        val winner =
            if (speechWinner != null &&
                combinedWinner != visualWinner &&
                visualWinner != "unknown" &&
                (visualScores[visualWinner] ?: 0.0) >= strongVisualWeight
            ) visualWinner
            else combinedWinner

        // Source attribution + visual support on the winning label.
        val vShare = visualScores[winner] ?: 0.0
        val sShare = speechScores[winner] ?: 0.0
        val total = vShare + sShare
        val source: Source
        val visualSupport: Double
        if (total <= 1e-9) {
            source = Source.VISION
            visualSupport = 0.0
        } else {
            val vf = vShare / total
            visualSupport = vf
            source = when {
                vf >= 0.7 -> Source.VISION
                vf <= 0.3 -> Source.SPEECH
                else -> Source.FUSED
            }
        }

        // Confidence — cautious, builds with evidence.
        val totalVisual = visualScores.values.sum().takeIf { it > 0 } ?: 1.0
        val agreement = (visualScores[winner] ?: 0.0) / totalVisual
        val vals = frames.map { valueFor(it, field) }
        val volatility = volatility(vals)
        val avgFrameConf = if (confidences.isEmpty()) 0.0 else confidences.average()
        val maturity = min(1.0, frames.size.toDouble() / max(1.0, maturityFullAt.toDouble()))

        var confidence = agreement * avgFrameConf * maturity * (1.0 - 0.4 * volatility)
        if (sShare > 0 && speechWinner == winner) confidence = min(1.0, confidence + 0.1)
        confidence = confidence.coerceIn(0.0, 0.95)

        // Stability — last K frames all equal AND low volatility.
        val lastK = vals.takeLast(stableRunFrames)
        val stable = lastK.toSet().size <= 1 && volatility <= 0.2
        val stability = if (stable) Stability.STABLE else Stability.FLUCTUATING

        val trend = trend(field, vals)
        val durationSec = updateHistory(field, winner, now, frames)

        return FieldState(
            value = winner,
            confidence = confidence,
            stability = stability,
            trend = trend,
            durationSec = durationSec,
            source = source,
            visualSupport = visualSupport,
        )
    }

    private fun computeList(
        frames: List<ObservationFrame>,
        field: String,
        now: Double,
        vocab: Set<String>,
        boosts: Map<String, Double>?,
    ): List<String> {
        val scores = HashMap<String, Double>()
        for (f in frames) {
            val w = recency(f.timestampSec, now) * max(0.05, f.confidence)
            val list = if (field == FIELD_BODY_PARTS) f.bodyPartsVisible else f.sceneRisk
            for (v in list) if (v in vocab) scores[v] = (scores[v] ?: 0.0) + w
        }
        if (boosts != null) {
            for ((k, extra) in boosts) if (k in vocab) scores[k] = (scores[k] ?: 0.0) + extra
        }
        val threshold = 0.15
        return scores.entries.filter { it.value >= threshold }.map { it.key }.sorted()
    }

    // ---- helpers ----------------------------------------------------------

    private fun recency(ts: Double, now: Double): Double {
        val age = max(0.0, now - ts)
        return 0.5.pow(age / recencyHalfLifeSec)
    }

    private fun updateHistory(
        field: String,
        newValue: String,
        now: Double,
        frames: List<ObservationFrame>,
    ): Double {
        val current = history[field]
        if (current == null || current.value != newValue) {
            // Walk back to find the start of the contiguous run matching the
            // new winner so duration survives a tracker warm-up.
            var entered = now
            for (f in frames.asReversed()) {
                if (valueFor(f, field) == newValue) entered = f.timestampSec else break
            }
            history[field] = History(value = newValue, enteredAt = entered)
            return now - entered
        }
        return now - current.enteredAt
    }

    private fun valueFor(f: ObservationFrame, field: String): String = when (field) {
        FIELD_BLEEDING -> f.bleeding
        FIELD_BREATHING -> f.breathing
        FIELD_CONSCIOUS -> f.conscious
        FIELD_PERSON_VISIBLE -> f.personVisible
        else -> "unknown"
    }

    private fun argmaxWithTiebreak(scores: Map<String, Double>, field: String): String? {
        if (scores.isEmpty()) return null
        val sev = SEVERITY[field].orEmpty()
        return scores.entries.maxWith(
            compareBy<Map.Entry<String, Double>> { it.value }
                .thenBy { sev[it.key] ?: 0 }
        ).key
    }

    private fun volatility(values: List<String>): Double {
        if (values.size < 2) return 0.0
        var flips = 0
        for (i in 1 until values.size) if (values[i] != values[i - 1]) flips++
        return flips.toDouble() / (values.size - 1)
    }

    private fun trend(field: String, values: List<String>): Trend {
        val sev = SEVERITY[field] ?: return Trend.UNKNOWN
        if (values.size < 3) return Trend.UNKNOWN
        val nums = values.mapNotNull { sev[it] }
        if (nums.size < 3) return Trend.UNKNOWN
        val half = nums.size / 2
        val firstAvg = nums.subList(0, half).average()
        val secondAvg = nums.subList(half, nums.size).average()
        val diff = secondAvg - firstAvg
        return when {
            diff >= 0.5 -> Trend.INCREASING
            diff <= -0.5 -> Trend.DECREASING
            else -> Trend.STABLE
        }
    }

    // ---- types ------------------------------------------------------------

    /** Return value of [snapshot]: per-field states + list fields + global aggregate. */
    data class Snapshot(
        val bleeding: FieldState,
        val breathing: FieldState,
        val conscious: FieldState,
        val personVisible: FieldState,
        val bodyPartsVisible: List<String>,
        val sceneRisk: List<String>,
        val globalConfidence: Double,
        val framesInBuffer: Int,
    )

    companion object {
        const val FIELD_BLEEDING = "bleeding"
        const val FIELD_BREATHING = "breathing"
        const val FIELD_CONSCIOUS = "conscious"
        const val FIELD_PERSON_VISIBLE = "person_visible"
        const val FIELD_BODY_PARTS = "body_parts_visible"
        const val FIELD_SCENE_RISK = "scene_risk"

        /**
         * Severity ordering, ported verbatim from Python _SEVERITY.
         * "unknown" sits at 1 so movement from unknown → no (unconscious)
         * still registers as increasing severity.
         */
        private val SEVERITY: Map<String, Map<String, Int>> = mapOf(
            FIELD_BLEEDING to mapOf("none" to 0, "unknown" to 1, "minor" to 1, "heavy" to 2),
            FIELD_BREATHING to mapOf("normal" to 0, "unknown" to 1, "abnormal" to 1, "none" to 2),
            FIELD_CONSCIOUS to mapOf("yes" to 0, "unknown" to 1, "no" to 2),
            FIELD_PERSON_VISIBLE to mapOf("no" to 0, "yes" to 1),
        )
    }
}
