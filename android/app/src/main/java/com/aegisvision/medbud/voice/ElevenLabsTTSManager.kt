package com.aegisvision.medbud.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.aegisvision.medbud.decision.UrgencyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ElevenLabs text-to-speech wrapper.
 *
 * Uses the low-latency `optimize_streaming_latency=4` endpoint; the response
 * is buffered into a cache-dir MP3 and played by [MediaPlayer]. Urgency is
 * translated into ElevenLabs `voice_settings` so the same sentence reads
 * differently at LOW vs CRITICAL levels — no text mangling needed.
 *
 * Threading:
 *   * [speak] is suspend; HTTP happens on [Dispatchers.IO], the
 *     [MediaPlayer] is created on [Dispatchers.Main].
 *   * [interrupt] is safe to call from any thread.
 */
class ElevenLabsTTSManager(
    private val context: Context,
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var currentFile: File? = null

    /**
     * Synthesize [text] and play it back. Automatically interrupts any
     * speech that was already playing. Returns quickly — playback continues
     * in the background via [MediaPlayer] callbacks.
     */
    suspend fun speak(text: String, urgency: UrgencyLevel) {
        if (text.isBlank()) return
        if (apiKey.isBlank() || voiceId.isBlank()) {
            Log.w(TAG, "ElevenLabs TTS credentials missing; skipping speak")
            return
        }

        interrupt()

        val audio = withContext(Dispatchers.IO) { fetchAudio(text, urgency) } ?: return
        withContext(Dispatchers.Main) { startPlayback(audio) }
    }

    /**
     * Phase 3.2 entry point: speak and suspend until playback completes,
     * errors, or the coroutine is cancelled (in which case playback is
     * interrupted). Used by the guidance loop so it can open a listening
     * window immediately after the last word.
     */
    suspend fun speakAwait(text: String, urgency: UrgencyLevel) {
        if (text.isBlank()) return
        if (apiKey.isBlank() || voiceId.isBlank()) {
            Log.w(TAG, "ElevenLabs TTS credentials missing; skipping speak")
            return
        }

        interrupt()
        val audio = withContext(Dispatchers.IO) { fetchAudio(text, urgency) } ?: return

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val tmp = File.createTempFile("tts_", ".mp3", context.cacheDir)
                try {
                    tmp.writeBytes(audio)
                } catch (t: Throwable) {
                    Log.w(TAG, "TTS temp file write failed", t)
                    tmp.delete()
                    if (cont.isActive) cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }

                val mp = MediaPlayer()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish() {
                    if (done.getAndSet(true)) return
                    try { mp.release() } catch (_: Throwable) {}
                    tmp.delete()
                    if (player === mp) player = null
                    if (currentFile === tmp) currentFile = null
                    if (cont.isActive) cont.resume(Unit)
                }
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build()
                    )
                    mp.setDataSource(tmp.absolutePath)
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnCompletionListener { finish() }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error $what/$extra")
                        finish(); true
                    }
                    mp.prepareAsync()
                    player = mp
                    currentFile = tmp
                } catch (t: Throwable) {
                    Log.w(TAG, "playback setup failed", t)
                    finish()
                }

                cont.invokeOnCancellation {
                    interrupt()
                    // finish() already handles release via the path above.
                }
            }
        }
    }

    /** Stop any currently-playing audio and release resources. */
    fun interrupt() {
        val p = player
        player = null
        try { p?.stop() } catch (_: Throwable) {}
        try { p?.release() } catch (_: Throwable) {}
        val f = currentFile
        currentFile = null
        try { f?.delete() } catch (_: Throwable) {}
    }

    // ---- HTTP -------------------------------------------------------------

    private fun fetchAudio(text: String, urgency: UrgencyLevel): ByteArray? {
        val settings = voiceSettingsFor(urgency)
        val body = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", JSONObject().apply {
                put("stability", settings.stability)
                put("similarity_boost", settings.similarityBoost)
                put("style", settings.style)
                put("use_speaker_boost", true)
            })
        }.toString().toRequestBody(JSON_MEDIA)

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId" +
            "?optimize_streaming_latency=4&output_format=mp3_44100_128"

        val req = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "TTS http ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return null
                }
                resp.body?.bytes()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TTS transport error", t)
            null
        }
    }

    // ---- playback ---------------------------------------------------------

    private fun startPlayback(audio: ByteArray) {
        val tmp = File.createTempFile("tts_", ".mp3", context.cacheDir)
        try {
            tmp.writeBytes(audio)
        } catch (t: Throwable) {
            Log.w(TAG, "TTS temp file write failed", t)
            tmp.delete()
            return
        }

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            mp.setDataSource(tmp.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Throwable) {}
                tmp.delete()
                if (player === it) player = null
                if (currentFile === tmp) currentFile = null
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra")
                true
            }
            mp.prepareAsync()
            player = mp
            currentFile = tmp
        } catch (t: Throwable) {
            Log.w(TAG, "playback setup failed", t)
            try { mp.release() } catch (_: Throwable) {}
            tmp.delete()
        }
    }

    // ---- voice settings ---------------------------------------------------

    private data class VoiceSettings(
        val stability: Double,
        val similarityBoost: Double,
        val style: Double,
    )

    /**
     * Rough urgency mapping. Lower stability reads as more expressive/urgent;
     * higher style layers on more dynamic delivery. Kept simple on purpose.
     */
    private fun voiceSettingsFor(urgency: UrgencyLevel): VoiceSettings = when (urgency) {
        UrgencyLevel.LOW       -> VoiceSettings(stability = 0.70, similarityBoost = 0.75, style = 0.0)
        UrgencyLevel.MODERATE  -> VoiceSettings(stability = 0.50, similarityBoost = 0.75, style = 0.2)
        UrgencyLevel.HIGH      -> VoiceSettings(stability = 0.35, similarityBoost = 0.80, style = 0.4)
        UrgencyLevel.CRITICAL  -> VoiceSettings(stability = 0.20, similarityBoost = 0.85, style = 0.6)
    }

    companion object {
        private const val TAG = "ElevenLabsTTS"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
