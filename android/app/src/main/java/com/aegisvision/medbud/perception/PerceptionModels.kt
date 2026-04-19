package com.aegisvision.medbud.perception

/**
 * Data classes ported from the Python perception layer.
 *
 * [ObservationFrame] is the VLM's per-frame result (one HTTP call → one instance).
 * [FieldState] is the tracker's per-field roll-up over the buffer + recent speech.
 * [PerceptionState] is what the UI observes via StateFlow.
 *
 * All values are intentionally [String]s (not enums) so that out-of-vocab
 * labels from the model can be sanitised without blowing up the parser.
 * The constants below define the valid vocabularies.
 */

object Vocab {
    val BLEEDING = setOf("none", "minor", "heavy", "unknown")
    val CONSCIOUS = setOf("yes", "no", "unknown")
    val BREATHING = setOf("normal", "abnormal", "none", "unknown")
    val PERSON_VISIBLE = setOf("yes", "no")
    val BODY_PARTS = setOf(
        "face", "head", "neck", "chest", "abdomen",
        "arm", "hand", "leg", "foot", "back"
    )
    val SCENE_RISKS = setOf(
        "fire", "smoke", "traffic", "water", "electrical",
        "crowd", "weapon", "fall", "cold", "heat", "none"
    )
}

enum class Stability { STABLE, FLUCTUATING }
enum class Trend { INCREASING, DECREASING, STABLE, UNKNOWN }
enum class Source { VISION, SPEECH, FUSED }

/**
 * One bounding-box detection from the VLM. Coordinates are normalized
 * [0,1] over the *frame as sent to the model* — translation to view
 * pixels is the overlay's job.
 */
data class Detection(
    val label: String,
    val severity: String = "",
    val confidence: Double = 0.0,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

/** One VLM response, sanitised. Directly comparable to Python Observation. */
data class ObservationFrame(
    val bleeding: String = "unknown",
    val conscious: String = "unknown",
    val breathing: String = "unknown",
    val bodyPartsVisible: List<String> = emptyList(),
    val sceneRisk: List<String> = emptyList(),
    val personVisible: String = "no",
    val confidence: Double = 0.0,
    val notes: String = "",
    val detections: List<Detection> = emptyList(),
    /** Monotonic wall-clock seconds when this frame was produced. */
    val timestampSec: Double = nowSec()
)

/** A single transcript chunk pushed into the fusion engine. */
data class SpeechEvent(
    val text: String,
    val timestampSec: Double = nowSec()
)

/** Per-field temporal state. The full roll-up for one medical field. */
data class FieldState(
    val value: String,
    val confidence: Double = 0.0,
    val stability: Stability = Stability.FLUCTUATING,
    val trend: Trend = Trend.UNKNOWN,
    val durationSec: Double = 0.0,
    val source: Source = Source.VISION,
    val visualSupport: Double = 0.0
) {
    companion object {
        fun unknown() = FieldState(value = "unknown")
        fun noPerson() = FieldState(value = "no")
    }
}

/** Output shared with the UI layer. Replaces the Python PipelineOutput. */
data class PerceptionState(
    val timestampSec: Double,
    val bleeding: FieldState,
    val breathing: FieldState,
    val conscious: FieldState,
    val personVisible: FieldState,
    val bodyPartsVisible: List<String>,
    val sceneRisk: List<String>,
    val globalConfidence: Double,
    val summary: String,
    val framesInBuffer: Int,
    val recentSpeech: String
) {
    companion object {
        fun empty() = PerceptionState(
            timestampSec = nowSec(),
            bleeding = FieldState.unknown(),
            breathing = FieldState.unknown(),
            conscious = FieldState.unknown(),
            personVisible = FieldState.noPerson(),
            bodyPartsVisible = emptyList(),
            sceneRisk = emptyList(),
            globalConfidence = 0.0,
            summary = "Awaiting first frame.",
            framesInBuffer = 0,
            recentSpeech = ""
        )
    }
}

internal fun nowSec(): Double = System.currentTimeMillis() / 1000.0
