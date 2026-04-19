package com.aegisvision.medbud.gemini

import org.json.JSONObject

/**
 * Event stream emitted by [GeminiLiveClient].
 *
 * These are the shapes of the bidi protocol messages Gemini Live sends
 * back over the WebSocket, normalised into Kotlin so UI / voice layers
 * don't have to touch JSON.
 */
sealed class GeminiEvent {
    /** WebSocket handshake complete, setup message sent. */
    object Open : GeminiEvent()

    /** Setup acknowledged — server is ready to accept realtime input. */
    object SetupComplete : GeminiEvent()

    /** Server closed the connection (clean or otherwise). */
    data class Closed(val reason: String) : GeminiEvent()

    /** An incremental text delta from the model. */
    data class TextDelta(val text: String) : GeminiEvent()

    /**
     * An incremental PCM16 audio chunk from the model's TTS. Default
     * sample rate for Gemini Live output is 24 kHz mono.
     */
    data class AudioDelta(val pcm16: ByteArray, val sampleRate: Int) : GeminiEvent()

    /** Server-side transcription of the *user's* audio input. */
    data class InputTranscription(val text: String, val isFinal: Boolean) : GeminiEvent()

    /** Server-side transcription of the *model's* audio output. */
    data class OutputTranscription(val text: String, val isFinal: Boolean) : GeminiEvent()

    /**
     * The current turn ended. [interrupted] is true when the server
     * aborted mid-response because the user started speaking.
     */
    data class TurnComplete(val interrupted: Boolean) : GeminiEvent()

    /** Tool-call request from the model (function calling). */
    data class ToolCall(val name: String, val args: JSONObject, val callId: String) : GeminiEvent()

    /** WebSocket-level error. The client will have closed by the time this fires. */
    data class Error(val cause: Throwable?, val detail: String) : GeminiEvent()

    /** Escape hatch for anything the client didn't decode — full JSON body. */
    data class Raw(val json: JSONObject) : GeminiEvent()
}

/** Response modalities Gemini Live can emit. */
enum class GeminiModality(val wire: String) {
    TEXT("TEXT"),
    AUDIO("AUDIO");
}

/** Initial session configuration sent as the first WS message. */
data class GeminiLiveSetup(
    val model: String,
    val responseModalities: List<GeminiModality> = listOf(GeminiModality.AUDIO),
    val voiceName: String = "Puck",
    val systemInstruction: String? = null,
    val enableInputTranscription: Boolean = true,
    val enableOutputTranscription: Boolean = true,
)
