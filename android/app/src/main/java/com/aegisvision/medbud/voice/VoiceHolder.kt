package com.aegisvision.medbud.voice

import android.content.Context
import android.util.Log
import com.aegisvision.medbud.BuildConfig
import com.aegisvision.medbud.guidance.AudioCaptureManager
import com.aegisvision.medbud.guidance.GuidanceLoopEngine
import com.aegisvision.medbud.guidance.RealtimeResponseListener
import com.aegisvision.medbud.perception.PerceptionHolder
import com.aegisvision.medbud.perception.STTManager

/**
 * Process-scoped singleton wiring together Phase 3.1 (TTS) and Phase 3.2
 * (speak-listen-decide loop). [ensureStarted] is idempotent and thread-safe.
 *
 * Deliberately does **not** attach [VoiceInstructionEngine.start] — the
 * [GuidanceLoopEngine] is the sole speaker once Phase 3.2 is on.
 */
object VoiceHolder {

    @Volatile private var _tts: ElevenLabsTTSManager? = null
    @Volatile private var _voice: VoiceInstructionEngine? = null
    @Volatile private var _guidance: GuidanceLoopEngine? = null
    @Volatile private var _listener: RealtimeResponseListener? = null

    val tts: ElevenLabsTTSManager? get() = _tts
    /** Retained for backwards compatibility — step index + lastSpoken helper. */
    val engine: VoiceInstructionEngine? get() = _voice
    val guidance: GuidanceLoopEngine? get() = _guidance
    val listener: RealtimeResponseListener? get() = _listener

    fun ensureStarted(context: Context) {
        if (_guidance != null) return
        synchronized(this) {
            if (_guidance != null) return

            val tts = ElevenLabsTTSManager(
                context = context.applicationContext,
                apiKey = BuildConfig.ELEVENLABS_API_KEY,
                voiceId = BuildConfig.ELEVENLABS_TTS_VOICE_ID,
                modelId = BuildConfig.ELEVENLABS_TTS_MODEL.ifBlank { "eleven_flash_v2_5" },
            )
            _tts = tts

            val voice = VoiceInstructionEngine(tts)
            _voice = voice  // not starting its auto-observer; the loop drives speech.

            val stt = STTManager(
                apiKey = BuildConfig.ELEVENLABS_API_KEY,
                modelId = BuildConfig.ELEVENLABS_STT_MODEL_ID.ifBlank { "scribe_v1" },
            )
            val audio = AudioCaptureManager(context.applicationContext)
            val listener = RealtimeResponseListener(audio = audio, stt = stt)
            _listener = listener

            // ChatGPT-style turn-taking: continuous mic, paused only while
            // TTS is speaking so the app doesn't transcribe its own voice.
            // NOTE: listener.start() is deliberately NOT called here — we
            // defer it until after the DAT video stream is live. Starting
            // AudioRecord during DAT session negotiation can cause Android
            // to renegotiate the BT audio profile and kill the DAT handshake.
            tts.onPlaybackStart = { listener.pause() }
            tts.onPlaybackEnd = { listener.resume() }

            val loop = GuidanceLoopEngine(
                tts = tts,
                voice = voice,
                listener = listener,
                repo = PerceptionHolder.repository,
            )
            loop.start()
            _guidance = loop
        }
    }

    /** Begin always-on voice capture. Call AFTER DAT stream is streaming. */
    fun startListening() {
        val l = _listener ?: return
        Log.i("VoiceHolder", "starting continuous listener")
        l.start()
    }

    /** Stop always-on voice capture. Call when the stream stops. */
    fun stopListening() {
        val l = _listener ?: return
        Log.i("VoiceHolder", "stopping continuous listener")
        l.stop()
    }
}
