package com.aegisvision.medbud.guidance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Low-latency, adaptive voice-capture pipeline.
 *
 *   Mic (16 kHz mono PCM, 20 ms frames)
 *     → EnergyVad   (speech? yes/no, cheap)
 *     → NoiseScorer (rolling SNR, noise floor, burst)
 *     → Router      (raw | light | drop, with 300 ms hysteresis)
 *     → UtteranceAccumulator (pre-roll, end-of-speech flush)
 *     → onUtterance(wav)
 *
 * Replaces the old fixed 3.5 s chunking. Typical end-of-speech flush now
 * fires ~600 ms after the user stops talking, instead of waiting out the
 * clock. Utterances are VAD-gated, so the STT never sees silence-only
 * clips.
 *
 * Light path engages Android's built-in `NoiseSuppressor` audio effect —
 * it's free platform voice isolation, toggled on only when the router
 * decides the noise floor warrants it. Heavy neural denoisers
 * (DeepFilterNet, RNNoise) are explicitly out of scope for the hackathon
 * — we rely on adaptive gating to degrade gracefully instead.
 *
 * Pause/resume coordinate with the TTS path: when the assistant is
 * speaking, frames are discarded at the boundary so the accumulator
 * doesn't pick up echo of our own voice. A short post-resume grace
 * period covers mic hardware tail.
 */
class AdaptiveAudioPipeline(private val context: Context) {

    /** Path the router selected for a given utterance. */
    enum class Path { RAW, LIGHT, DROP }

    /** One completed user utterance, ready for STT. */
    data class Utterance(
        val wav: ByteArray,
        val noiseFloorDb: Double,
        val snrDb: Double,
        val path: Path,
    )

    @Volatile private var paused: Boolean = false
    @Volatile private var cleanFromMs: Long = 0L

    fun pause() {
        paused = true
        cleanFromMs = Long.MAX_VALUE
    }

    fun resume() {
        paused = false
        cleanFromMs = System.currentTimeMillis() + POST_TTS_GRACE_MS
    }

    /**
     * Run the capture loop until the coroutine is cancelled, emitting one
     * [Utterance] per end-of-speech boundary. Safe to call multiple times
     * serially; not reentrant.
     */
    @SuppressLint("MissingPermission")
    suspend fun run(onUtterance: suspend (Utterance) -> Unit) = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; pipeline no-op"); return@withContext
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.w(TAG, "getMinBufferSize failed"); return@withContext
        }
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 4)
        val record = try {
            // IMPORTANT: use MIC, not VOICE_RECOGNITION / VOICE_COMMUNICATION.
            // The non-MIC sources are treated as "voice call audio" by some
            // BT stacks, which triggers A2DP → SCO renegotiation to grab
            // the glasses' mic — that kills the active A2DP stream the DAT
            // session is riding on, and the glasses drop the session with a
            // double beep. MIC lets the phone record from its own built-in
            // mic without touching BT routing.
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CFG, ENCODING, bufSize,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init threw", t); return@withContext
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialised")
            try { record.release() } catch (_: Throwable) {}
            return@withContext
        }

        // NOTE: NoiseSuppressor is intentionally NOT attached here.
        // Creating or toggling the effect on an active AudioRecord
        // session causes the Android audio framework to rebuild the
        // capture graph on some devices — and on BT A2DP stacks that
        // rebuild includes a route re-check that kills the DAT video
        // session (orange LED → stream drops). If we ever want platform
        // voice isolation back, it has to be enabled BEFORE the DAT
        // session is set up, not while the stream is live.
        val ns: NoiseSuppressor? = null
        Log.i(TAG, "pipeline start — NoiseSuppressor disabled for BT safety")

        val vad = EnergyVad()
        val scorer = NoiseScorer()
        val router = Router()
        val utter = UtteranceAccumulator()

        val frame = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                if (n < frame.size) continue

                // Mute / post-TTS grace gate. Discards frames so the
                // accumulator never sees echo of our own TTS voice.
                val now = System.currentTimeMillis()
                val frameStartMs = now - FRAME_MS
                if (paused || frameStartMs < cleanFromMs) {
                    utter.reset()   // any in-flight utterance is no longer trustworthy
                    continue
                }

                val speech = vad.process(frame)
                val score = scorer.update(frame, speech)
                val path = router.pick(score, speech)

                // Apply/disable platform NS based on router decision.
                // Only toggle when it actually changes — setEnabled has a
                // small cost and isn't meant to be called every frame.
                ns?.let { effect ->
                    val shouldEnable = path == Path.LIGHT
                    if (effect.enabled != shouldEnable) effect.enabled = shouldEnable
                }

                if (utter.feed(frame, speech, path)) {
                    val out = utter.takeUtterance(score)
                    Log.i(
                        TAG,
                        "utterance ${out.wav.size}B path=${out.path.name} " +
                            "snr=${"%.1f".format(out.snrDb)} floor=${"%.1f".format(out.noiseFloorDb)}dB",
                    )
                    onUtterance(out)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pipeline loop error", t)
        } finally {
            try { ns?.release() } catch (_: Throwable) {}
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            Log.i(TAG, "pipeline stopped")
        }
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    // ──────────────────────────────────────────────────────────── VAD

    /**
     * Cheap energy-based VAD. A running noise floor tracks quiet moments
     * (slow EMA, fast-down to catch dips); a frame is "speech" when its
     * energy runs [THRESHOLD_OVER_FLOOR_DB] dB over that floor.
     *
     * Not as accurate as Silero or WebRTC VAD but adequate for start /
     * end-of-speech gating, costs ~microseconds per frame, and has zero
     * model weights to ship.
     */
    private class EnergyVad {
        private var floorDb = -60.0
        fun process(frame: ShortArray): Boolean {
            val db = frameDb(frame)
            // Asymmetric EMA: track dips quickly so a brief lull doesn't
            // trip the "speech" threshold forever.
            floorDb = if (db < floorDb) 0.15 * db + 0.85 * floorDb
                      else              0.02 * db + 0.98 * floorDb
            return db > floorDb + THRESHOLD_OVER_FLOOR_DB
        }
        companion object { private const val THRESHOLD_OVER_FLOOR_DB = 8.0 }
    }

    // ──────────────────────────────────────────────────────────── Noise scorer

    private data class NoiseScore(
        val snrDb: Double,
        val floorDb: Double,
        val voicedRatio: Double,
        val burst: Boolean,
    )

    /**
     * Rolling SNR + burst detection. Separate EMAs for the noise floor
     * (during non-speech frames) and the speech envelope (during speech
     * frames) — subtracting gives a coarse SNR estimate that drives the
     * router. A 500 ms voiced-ratio window feeds the hysteresis.
     */
    private class NoiseScorer {
        private var floorDb = -50.0
        private var speechDb = -30.0
        private val recentSpeech = ArrayDeque<Boolean>()

        fun update(frame: ShortArray, speech: Boolean): NoiseScore {
            val db = frameDb(frame)
            val prevFloor = floorDb
            if (!speech) floorDb = 0.05 * db + 0.95 * floorDb
            if (speech)  speechDb = 0.10 * db + 0.90 * speechDb
            recentSpeech.addLast(speech)
            if (recentSpeech.size > VOICED_WINDOW_FRAMES) recentSpeech.removeFirst()
            val voiced = recentSpeech.count { it } / recentSpeech.size.toDouble()
            val burst = db > prevFloor + BURST_JUMP_DB
            return NoiseScore(
                snrDb = speechDb - floorDb,
                floorDb = floorDb,
                voicedRatio = voiced,
                burst = burst,
            )
        }
        companion object {
            private const val VOICED_WINDOW_FRAMES = 25    // 500 ms
            private const val BURST_JUMP_DB = 20.0
        }
    }

    // ──────────────────────────────────────────────────────────── Router

    private class Router {
        private var path: Path = Path.RAW
        private var sinceMs: Long = 0L

        fun pick(score: NoiseScore, speech: Boolean): Path {
            val desired = when {
                !speech && score.voicedRatio < 0.05          -> Path.DROP
                score.snrDb > 15 && score.voicedRatio > 0.4  -> Path.RAW
                else                                         -> Path.LIGHT
            }
            val now = System.currentTimeMillis()
            if (desired != path && (now - sinceMs) >= HYSTERESIS_MS) {
                path = desired
                sinceMs = now
            }
            return path
        }
        companion object { private const val HYSTERESIS_MS = 300L }
    }

    // ──────────────────────────────────────────────────────────── Accumulator

    /**
     * Assembles frames into a single WAV per utterance using VAD. Keeps
     * a 300 ms pre-roll so the first phoneme isn't clipped. Flushes when
     * silence runs longer than [END_SILENCE_FRAMES] or the utterance
     * hits the hard cap.
     *
     * `feed` returns true when an utterance is ready; call `takeUtterance`
     * to consume it.
     */
    private class UtteranceAccumulator {
        private val preRoll = ArrayDeque<ShortArray>()
        private val buf = ByteArrayOutputStream()
        private var silenceFrames = 0
        private var frames = 0
        private var started = false
        private var dominantPath: Path = Path.RAW

        fun feed(frame: ShortArray, speech: Boolean, path: Path): Boolean {
            preRoll.addLast(frame.copyOf())
            if (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()

            if (!started) {
                if (!speech) return false
                // Speech onset: dump pre-roll into the utterance.
                for (p in preRoll) appendFrame(p)
                frames = preRoll.size
                silenceFrames = 0
                started = true
                dominantPath = path
                return false
            }

            appendFrame(frame)
            frames += 1
            if (speech) {
                silenceFrames = 0
                // Upgrade path if router tightened mid-utterance.
                if (path.ordinal > dominantPath.ordinal) dominantPath = path
            } else {
                silenceFrames += 1
            }
            return silenceFrames >= END_SILENCE_FRAMES || frames >= MAX_FRAMES
        }

        fun takeUtterance(score: NoiseScore): Utterance {
            val pcm = buf.toByteArray()
            reset()
            return Utterance(
                wav = wrapWav(pcm),
                noiseFloorDb = score.floorDb,
                snrDb = score.snrDb,
                path = dominantPath,
            )
        }

        fun reset() {
            buf.reset()
            silenceFrames = 0
            frames = 0
            started = false
            dominantPath = Path.RAW
        }

        private fun appendFrame(frame: ShortArray) {
            val bb = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in frame) bb.putShort(s)
            buf.write(bb.array())
        }

        companion object {
            private const val PREROLL_FRAMES = 15        // 300 ms
            private const val END_SILENCE_FRAMES = 30    // 600 ms
            private const val MAX_FRAMES = 400           // 8 s hard cap
        }
    }

    // ──────────────────────────────────────────────────────────── WAV + dB helpers

    companion object {
        private const val TAG = "AdaptiveAudio"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20L
        private const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS / 1000L).toInt()  // 320
        private const val CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val POST_TTS_GRACE_MS = 1_500L

        private fun frameDb(frame: ShortArray): Double {
            var sumSq = 0.0
            for (s in frame) sumSq += (s.toDouble() * s.toDouble())
            val rms = sqrt(sumSq / frame.size)
            if (rms < 1.0) return -80.0
            return 20.0 * ln(rms / 32_768.0) / ln(10.0)
        }

        private fun wrapWav(pcm: ByteArray): ByteArray {
            if (pcm.isEmpty()) return ByteArray(0)
            val channels = 1
            val bitsPerSample = 16
            val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
            val totalDataLen = pcm.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
            }.array()
            return header + pcm
        }
    }
}
