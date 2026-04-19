package com.aegisvision.medbud.guidance

import android.util.Log
import com.aegisvision.medbud.perception.STTManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on voice listener — ChatGPT-voice-chat style.
 *
 * Instead of fixed-duration chunks, this consumes VAD-gated utterances
 * from [AdaptiveAudioPipeline]. Each utterance is already pre-rolled and
 * trimmed at end-of-speech, so STT fires within ~600 ms of the user
 * finishing a sentence instead of waiting out a 3.5 s clock.
 *
 * [pause] / [resume] forward to the pipeline so the mic pipeline mutes
 * itself while TTS is playing (the pipeline also honours a post-resume
 * grace window to discard echo-tainted frames).
 */
class RealtimeResponseListener(
    private val pipeline: AdaptiveAudioPipeline,
    private val stt: STTManager,
) {
    data class Result(
        val response: UserResponseType,
        val rawTranscript: String,
    )

    val transcripts: SharedFlow<Result> get() = _transcripts
    private val _transcripts = MutableSharedFlow<Result>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamJob: Job? = null

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        streamJob = scope.launch { loop() }
    }

    fun stop() {
        running = false
        streamJob?.cancel()
        streamJob = null
    }

    fun pause() {
        pipeline.pause()
        Log.i(TAG, "paused (TTS playing)")
    }

    fun resume() {
        pipeline.resume()
        Log.i(TAG, "resumed")
    }

    /**
     * Await the next transcript within [windowMs]. The always-on pipeline
     * will fill the buffer naturally as the user talks.
     */
    suspend fun listenOnce(windowMs: Long = 5_000L): Result = withContext(Dispatchers.Default) {
        withTimeoutOrNull(windowMs) { transcripts.first() }
            ?: Result(UserResponseType.NO_RESPONSE, "")
    }

    // ---- loop internals ---------------------------------------------------

    private suspend fun loop() {
        while (running) {
            try {
                pipeline.run { utterance ->
                    // Transcribe in a separate coroutine so the pipeline
                    // keeps accumulating the next utterance.
                    scope.launch { processUtterance(utterance) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pipeline errored — retrying in 1s", t)
            }
            if (running) delay(1_000L)
        }
    }

    private suspend fun processUtterance(utterance: AdaptiveAudioPipeline.Utterance) {
        val text = try {
            stt.transcribe(utterance.wav, contentType = "audio/wav")?.trim().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "STT error", t); return
        }
        if (text.isEmpty()) return
        val resp = ResponseInterpreter.parse(text)
        Log.i(TAG, "heard [${resp.name}] (path=${utterance.path}) \"$text\"")
        _transcripts.tryEmit(Result(resp, text))
    }

    companion object { private const val TAG = "RealtimeListener" }
}
