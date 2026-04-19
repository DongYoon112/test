package com.aegisvision.medbud.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.aegisvision.medbud.decision.UrgencyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /**
     * Optional hooks to pause/resume a continuous mic listener while TTS
     * is playing. Prevents the app from transcribing its own voice.
     */
    @Volatile var onPlaybackStart: (() -> Unit)? = null
    @Volatile var onPlaybackEnd: (() -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var currentFile: File? = null

    /**
     * True from the moment MediaPlayer.start() fires until onCompletion
     * or onError runs. When true, [interrupt] is suppressed so nothing
     * can cut an utterance off mid-word. The speakAwait coroutine is
     * also wrapped in NonCancellable while playing so cycle cancellation
     * can't unwind through us either.
     */
    @Volatile private var isSpeaking: Boolean = false

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

        // Pause the continuous listener FIRST — before interrupt + fetch —
        // so an utterance during the 300–500 ms fetch window can't cancel
        // the cycle and cut playback off at the knees.
        onPlaybackStart?.invoke()
        try {
        interrupt()
        val audio = withContext(Dispatchers.IO) { fetchAudio(text, urgency) } ?: return
        // Wrap playback in NonCancellable so an outer cycle.cancel() can't
        // unwind through a sentence in flight — once we start speaking,
        // we finish. Explicit forceInterrupt() on shutdown still works
        // because it stops the MediaPlayer directly via onError, which
        // resumes the continuation normally.
        withContext(NonCancellable + Dispatchers.Main) {
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
                Log.i(TAG, "TTS received ${audio.size} bytes; starting MediaPlayer")

                val mp = MediaPlayer()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish() {
                    if (done.getAndSet(true)) return
                    // Speech is done — re-enable interrupt() for the next
                    // cycle. Must flip BEFORE releasing the player so any
                    // interrupt() racing with completion can clean up.
                    isSpeaking = false
                    try { mp.release() } catch (_: Throwable) {}
                    tmp.delete()
                    if (player === mp) player = null
                    if (currentFile === tmp) currentFile = null
                    // onPlaybackEnd is called by the outer `finally` so it
                    // fires on every path (including cancellation).
                    if (cont.isActive) cont.resume(Unit)
                }
                try {
                    // USAGE_MEDIA routes through the A2DP/BLE-audio path, which
                    // is what the Ray-Ban Meta speakers register as.
                    // USAGE_ASSISTANT was going to the voice-communication stream
                    // and skipping the glasses entirely.
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    mp.setDataSource(tmp.absolutePath)
                    mp.setVolume(1.0f, 1.0f)
                    // If a Bluetooth output (A2DP / BLE audio / hearing aid) is
                    // available, pin playback to it so TTS comes out of the
                    // glasses even if the phone's default route drifts.
                    pickBluetoothOutput()?.let { dev ->
                        try {
                            if (mp.setPreferredDevice(dev)) {
                                Log.i(TAG, "TTS → preferred device: ${dev.productName} (type=${dev.type})")
                            } else {
                                Log.w(TAG, "setPreferredDevice returned false for ${dev.productName}")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "setPreferredDevice threw", t)
                        }
                    } ?: Log.i(TAG, "No Bluetooth output device found — using phone default")
                    mp.setOnPreparedListener {
                        Log.i(TAG, "MediaPlayer prepared; starting playback (duration=${it.duration}ms)")
                        // Flag playback as "in flight" BEFORE start() so
                        // any interrupt() racing with start sees the flag
                        // and backs off. Cleared in finish().
                        isSpeaking = true
                        it.start()
                    }
                    mp.setOnCompletionListener {
                        Log.i(TAG, "MediaPlayer playback complete")
                        finish()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer onError: what=$what extra=$extra — audio will NOT be heard")
                        finish(); true
                    }
                    mp.setOnInfoListener { _, what, extra ->
                        Log.i(TAG, "MediaPlayer onInfo: what=$what extra=$extra")
                        false
                    }
                    mp.prepareAsync()
                    player = mp
                    currentFile = tmp
                } catch (t: Throwable) {
                    Log.e(TAG, "playback setup failed", t)
                    finish()
                }

                cont.invokeOnCancellation {
                    interrupt()
                    // finish() already handles release via the path above.
                }
            }
        }
        } finally {
            onPlaybackEnd?.invoke()
        }
    }

    /**
     * Stop any currently-playing audio and release resources. Suppressed
     * while an utterance is actively playing — once speech starts, it
     * finishes. Use [forceInterrupt] for shutdown / reset paths that must
     * cut speech regardless.
     */
    fun interrupt() {
        if (isSpeaking) {
            Log.i(TAG, "interrupt() suppressed — utterance in progress")
            return
        }
        val p = player
        player = null
        try { p?.stop() } catch (_: Throwable) {}
        try { p?.release() } catch (_: Throwable) {}
        val f = currentFile
        currentFile = null
        try { f?.delete() } catch (_: Throwable) {}
    }

    /** Unconditional stop. Only call this on shutdown / reset paths. */
    fun forceInterrupt() {
        isSpeaking = false
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
            val t0 = System.currentTimeMillis()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "TTS http ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return null
                }
                val bytes = resp.body?.bytes()
                Log.i(TAG, "TTS http 200 in ${System.currentTimeMillis() - t0}ms, ${bytes?.size ?: 0} bytes")
                bytes
            }
        } catch (t: Throwable) {
            Log.e(TAG, "TTS transport error", t)
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

    // ---- audio routing ----------------------------------------------------

    /**
     * Prefer routing TTS playback to a Bluetooth audio output (the Ray-Ban
     * Meta speakers, a BT headset, etc.) over the phone's built-in speaker.
     * Returns the first BT output device we find, or null if none present.
     */
    private fun pickBluetoothOutput(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val outputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (t: Throwable) {
            Log.w(TAG, "getDevices failed", t); return null
        }
        val btTypes = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
                add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
            }
        }
        // Prefer A2DP (classic BT music), then BLE audio, then anything else BT.
        val ordered = outputs.sortedBy {
            when (it.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
                AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> 2
                AudioDeviceInfo.TYPE_HEARING_AID -> 3
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
                else -> 99
            }
        }
        return ordered.firstOrNull { it.type in btTypes }
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
