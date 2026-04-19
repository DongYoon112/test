package com.aegisvision.medbud.perception

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aegisvision.medbud.BuildConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin ViewModel wrapping the process-wide [PerceptionRepository].
 *
 * A single repository instance is created lazily on first access and kept
 * alive in [PerceptionHolder] so that the Activity that actually *feeds*
 * frames (MainActivity) and any Activity that *observes* state (the debug
 * screen) share the same tracker.
 */
class PerceptionViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<PerceptionState> = PerceptionHolder.repository.state

    fun submitFrame(jpeg: ByteArray) = PerceptionHolder.repository.submitFrame(jpeg)

    fun submitTranscript(text: String) = PerceptionHolder.repository.submitTranscript(text)

    suspend fun submitAudio(bytes: ByteArray, contentType: String = "audio/wav"): String =
        PerceptionHolder.repository.submitAudio(bytes, contentType)
}

/** Process-scoped singleton so the tracker survives Activity recreations. */
object PerceptionHolder {
    val repository: PerceptionRepository by lazy {
        val vision = VisionAnalysisManager(
            apiKey = BuildConfig.OPENAI_API_KEY,
            model = BuildConfig.OPENAI_VISION_MODEL.ifBlank { "gpt-4o-mini" },
        )
        val stt = STTManager(
            apiKey = BuildConfig.ELEVENLABS_API_KEY,
            modelId = BuildConfig.ELEVENLABS_STT_MODEL_ID.ifBlank { "scribe_v1" },
        )
        PerceptionRepository(vision = vision, stt = stt).also { it.start() }
    }
}
