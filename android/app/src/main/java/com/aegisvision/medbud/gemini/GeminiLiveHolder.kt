package com.aegisvision.medbud.gemini

import com.aegisvision.medbud.BuildConfig

/**
 * Process-scoped singleton for [GeminiLiveClient]. Mirrors
 * [PerceptionHolder] / [VoiceHolder] / [IncidentHolder] so integration
 * code elsewhere doesn't have to plumb credentials through.
 *
 * Idempotent: [ensureCreated] builds the client once with the
 * BuildConfig-supplied credentials. The client is *not* auto-connected
 * — call [GeminiLiveClient.connect] when the integration layer is
 * ready to open a session.
 */
object GeminiLiveHolder {

    @Volatile private var _client: GeminiLiveClient? = null

    val client: GeminiLiveClient? get() = _client

    fun ensureCreated(): GeminiLiveClient? {
        _client?.let { return it }
        synchronized(this) {
            _client?.let { return it }
            val key = BuildConfig.GEMINI_API_KEY
            val model = BuildConfig.GEMINI_LIVE_MODEL.ifBlank { DEFAULT_MODEL }
            if (key.isBlank()) return null
            val fresh = GeminiLiveClient(apiKey = key, model = model)
            _client = fresh
            return fresh
        }
    }

    /** Default voice from BuildConfig, falling back to "Puck". */
    fun defaultVoice(): String = BuildConfig.GEMINI_LIVE_VOICE.ifBlank { "Puck" }

    private const val DEFAULT_MODEL = "gemini-2.0-flash-exp"
}
