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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on voice listener — ChatGPT-voice-chat style.
 *
 * Holds the mic open continuously. Every [chunkMs] of audio is transcribed
 * via ElevenLabs STT and parsed by [ResponseInterpreter]; every result is
 * emitted on [transcripts]. The guidance loop + the fusion engine both
 * subscribe, so the user can just talk whenever — no button presses, no
 * explicit "listen now" windows.
 *
 * [pause] / [resume] are used by the TTS path to avoid the mic picking up
 * the AI's own voice during playback.
 */
class RealtimeResponseListener(
    private val audio: AudioCaptureManager,
    private val stt: STTManager,
    private val chunkMs: Long = 3_500L,
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
    @Volatile private var paused = false

    /** Start the always-on loop. Idempotent. */
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

    /** Mute the mic (e.g. while TTS is playing to avoid echo). */
    fun pause() {
        if (!paused) {
            paused = true
            Log.i(TAG, "paused (TTS playing)")
        }
    }

    fun resume() {
        if (paused) {
            paused = false
            Log.i(TAG, "resumed")
        }
    }

    /**
     * Await the next transcript within [windowMs]. Guidance loop uses this
     * instead of the old one-shot record; it just grabs whatever the
     * always-on stream produces next.
     */
    suspend fun listenOnce(windowMs: Long = 5_000L): Result = withContext(Dispatchers.Default) {
        withTimeoutOrNull(windowMs) { transcripts.first() }
            ?: Result(UserResponseType.NO_RESPONSE, "")
    }

    // ---- loop internals ---------------------------------------------------

    private suspend fun loop() {
        while (running) {
            try {
                audio.streamWav(chunkMs) { wav ->
                    if (paused) return@streamWav
                    // Transcribe in a separate coroutine so the recording
                    // loop keeps pumping.
                    scope.launch { processChunk(wav) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "streamWav errored — retrying in 1s", t)
            }
            if (running) delay(1_000L)   // e.g. permission wasn't granted yet
        }
    }

    private suspend fun processChunk(wav: ByteArray) {
        val text = try {
            stt.transcribe(wav, contentType = "audio/wav")?.trim().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "STT error", t); return
        }
        if (text.isEmpty()) return
        val resp = ResponseInterpreter.parse(text)
        Log.i(TAG, "heard: [${resp.name}] \"$text\"")
        _transcripts.tryEmit(Result(resp, text))
    }

    companion object { private const val TAG = "RealtimeListener" }
}
