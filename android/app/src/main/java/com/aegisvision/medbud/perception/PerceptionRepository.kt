package com.aegisvision.medbud.perception

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrator — port of Python `pipeline.Pipeline` as a pure Android class.
 *
 * Owns:
 *   • TemporalStateTracker
 *   • FusionEngine
 *   • a single "latest pending frame" slot (drops older frames if behind)
 *   • one long-running worker coroutine that runs VLM + speech fusion +
 *     tracker snapshot + StateFlow emit on each cycle
 *
 * UI layer observes [state] directly; no HTTP, no IPC, no service boundary.
 */
class PerceptionRepository(
    private val vision: VisionAnalysisManager,
    private val stt: STTManager,
    private val minIntervalMs: Long = 800,
    bufferCapacity: Int = ObservationBuffer.DEFAULT_CAPACITY,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = TemporalStateTracker(bufferCapacity = bufferCapacity)
    private val fusion = FusionEngine()

    private val pendingLock = Mutex()
    private var pending: ByteArray? = null
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    private var workerJob: Job? = null
    private var frameCounter = 0
    private var lastVlmCallMs = 0L

    private val _state = MutableStateFlow(PerceptionState.empty())
    val state: StateFlow<PerceptionState> = _state.asStateFlow()

    // ---- lifecycle --------------------------------------------------------

    fun start() {
        if (workerJob != null) return
        workerJob = scope.launch { workerLoop() }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        scope.cancel()
    }

    // ---- ingestion --------------------------------------------------------

    /** Push the newest frame. Any previously queued frame is dropped. */
    fun submitFrame(jpeg: ByteArray) {
        scope.launch {
            pendingLock.withLock { pending = jpeg }
            wake.trySend(Unit)
        }
    }

    /** Submit a short audio clip for transcription. Returns the transcript. */
    suspend fun submitAudio(audio: ByteArray, contentType: String = "audio/wav"): String {
        val text = stt.transcribe(audio, contentType) ?: return ""
        if (text.isNotEmpty()) fusion.addTranscript(text)
        return text
    }

    /** Fast path: the caller already has a transcript (e.g. on-device STT). */
    fun submitTranscript(text: String) {
        fusion.addTranscript(text)
    }

    // ---- worker -----------------------------------------------------------

    private suspend fun workerLoop() {
        while (scope.isActive()) {
            wake.receive()

            val sinceLast = System.currentTimeMillis() - lastVlmCallMs
            if (sinceLast < minIntervalMs) {
                kotlinx.coroutines.delay(minIntervalMs - sinceLast)
            }

            val jpeg = pendingLock.withLock {
                val j = pending
                pending = null
                j
            } ?: continue

            lastVlmCallMs = System.currentTimeMillis()
            frameCounter += 1

            val obs = try {
                vision.describe(jpeg, frameCounter)
            } catch (t: Throwable) {
                Log.w(TAG, "VLM call raised", t); null
            }
            if (obs == null) continue

            // Inject speech-derived boosts before the next snapshot, so the
            // tracker considers them both for voting and for source attribution.
            fusion.apply(
                applyBoost = { f, l, w -> tracker.applySpeechBoost(f, l, w) },
                applyScene = { r, w -> tracker.applySceneBoost(r, w) },
            )
            tracker.addFrame(obs)
            val snap = tracker.snapshot()
            val summary = summarise(snap)
            _state.value = PerceptionState(
                timestampSec = nowSec(),
                bleeding = snap.bleeding,
                breathing = snap.breathing,
                conscious = snap.conscious,
                personVisible = snap.personVisible,
                bodyPartsVisible = snap.bodyPartsVisible,
                sceneRisk = snap.sceneRisk,
                globalConfidence = snap.globalConfidence,
                summary = summary,
                framesInBuffer = snap.framesInBuffer,
                recentSpeech = fusion.recentText(),
            )
        }
    }

    private fun CoroutineScope.isActive(): Boolean = this.coroutineContext[Job]?.isActive == true

    companion object { private const val TAG = "PerceptionRepo" }
}

/**
 * Prose summary — port of Python `_summarize`. Always observation-only,
 * never diagnostic. Flags speech-sourced findings explicitly.
 */
internal fun summarise(snap: TemporalStateTracker.Snapshot): String {
    if (snap.personVisible.value == "no") return "No person in frame."
    val parts = mutableListOf<String>()

    val b = snap.bleeding
    if (b.value != "unknown" && b.value != "none") {
        var phrase = "${b.value} bleeding"
        when (b.trend) {
            Trend.INCREASING -> phrase += ", appears to be increasing"
            Trend.DECREASING -> phrase += ", appears to be improving"
            else -> {}
        }
        parts += phrase
    }

    val c = snap.conscious
    when (c.value) {
        "no" -> parts += if (c.trend == Trend.INCREASING) "consciousness deteriorating"
                         else "appears unconscious"
        "yes" -> parts += "conscious"
    }

    val br = snap.breathing
    if (br.value != "unknown" && br.value != "normal") {
        var phrase = if (br.value == "none") "possibly not breathing" else "breathing abnormal"
        when (br.source) {
            Source.SPEECH -> phrase += " (reported by voice; not visually confirmed)"
            Source.FUSED -> phrase += " (voice + visual)"
            else -> {}
        }
        parts += phrase
    }

    if (snap.sceneRisk.isNotEmpty()) {
        parts += "scene risk: " + snap.sceneRisk.joinToString(", ")
    }

    return if (parts.isEmpty()) "Person visible; no acute signs detected."
           else "Person visible; " + parts.joinToString("; ") + "."
}
