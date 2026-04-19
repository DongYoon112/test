package com.aegisvision.medbud.guidance

import android.util.Log
import com.aegisvision.medbud.perception.STTManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bundles mic capture + STT + rule-based parsing into a single short
 * listening cycle.
 *
 * MVP implementation is "batched real-time":
 *   1. record a fixed-length window (≤ 6 s)
 *   2. upload to ElevenLabs STT
 *   3. run [ResponseInterpreter]
 *
 * The class is shaped so it can later be swapped for a true streaming
 * WebSocket path without disturbing [GuidanceLoopEngine] — only the
 * [listenOnce] return contract matters externally.
 */
class RealtimeResponseListener(
    private val audio: AudioCaptureManager,
    private val stt: STTManager,
) {
    /**
     * One complete listen cycle. Returns both the category and the raw
     * transcript so the caller can optionally push the transcript into
     * the perception fusion engine (keywords like "he's breathing" are
     * valuable signals there even though the loop parses them too).
     */
    data class Result(
        val response: UserResponseType,
        val rawTranscript: String,
    )

    suspend fun listenOnce(windowMs: Long = DEFAULT_WINDOW_MS): Result {
        val wav = audio.captureWav(windowMs)
        if (wav.isEmpty()) {
            Log.w(TAG, "empty audio window; returning NO_RESPONSE")
            return Result(UserResponseType.NO_RESPONSE, "")
        }
        val transcript = withContext(Dispatchers.IO) {
            stt.transcribe(wav, contentType = "audio/wav")
        }?.trim().orEmpty()

        val parsed = ResponseInterpreter.parse(transcript)
        return Result(parsed, transcript)
    }

    companion object {
        private const val TAG = "RealtimeListener"
        private const val DEFAULT_WINDOW_MS = 5_000L
    }
}
