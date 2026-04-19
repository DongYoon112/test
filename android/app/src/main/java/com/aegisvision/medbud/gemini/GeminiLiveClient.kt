package com.aegisvision.medbud.gemini

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Gemini Live backbone.
 *
 * Opens a WebSocket to the `BidiGenerateContent` endpoint, sends the
 * initial [GeminiLiveSetup] message, and exposes:
 *   * [events] — a [SharedFlow] of typed [GeminiEvent]s
 *   * [sendAudio] / [sendImage] — realtime binary input (base64-wrapped)
 *   * [sendText] — text-turn input
 *   * [disconnect] — clean close
 *
 * Not wired into the guidance loop. Phase-3 voice and perception paths
 * are untouched. This is just the transport; a later integration layer
 * can adapt it into the existing pipelines.
 *
 * Thread-safety: [connect] / [disconnect] should be called from a
 * coordinating thread. `send*` can be called from any thread; OkHttp
 * serialises writes internally.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
) {
    val events: SharedFlow<GeminiEvent> get() = _events
    private val _events = MutableSharedFlow<GeminiEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // WebSockets need effectively-infinite read timeout; keep a ping
    // frame so intermediaries don't silently drop the connection.
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected: Boolean = false

    val isConnected: Boolean get() = connected

    /**
     * Open the WebSocket and send the initial setup. If already
     * connected, this is a no-op. Events emit on [events] as soon as
     * the server starts talking back.
     */
    fun connect(setup: GeminiLiveSetup = defaultSetup()) {
        if (connected || apiKey.isBlank()) {
            if (apiKey.isBlank()) Log.w(TAG, "GEMINI_API_KEY missing; connect() no-op")
            return
        }
        val url = "$WS_BASE?key=$apiKey"
        val request = Request.Builder().url(url).build()
        ws = http.newWebSocket(request, Listener(setup))
    }

    /** Close the session. Safe to call even if never connected. */
    fun disconnect(reason: String = "client disconnect") {
        val socket = ws
        ws = null
        connected = false
        try { socket?.close(1000, reason) } catch (_: Throwable) {}
    }

    /**
     * Push one audio chunk to the server. Expected format: raw
     * little-endian 16-bit PCM, mono, 16 kHz. The server transcribes it
     * (if enabled) and may begin responding before a turn boundary.
     */
    fun sendAudio(pcm16: ByteArray) {
        if (!connected || pcm16.isEmpty()) return
        val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().put(JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", b64)
                }))
            })
        }
        ws?.send(msg.toString())
    }

    /**
     * Push a single JPEG/PNG frame as multimodal input. Any reasonable
     * resolution works — Gemini resizes server-side. Use this for
     * video-frame-at-a-time scenarios; don't flood (~1-2 fps is enough).
     */
    fun sendImage(jpegBytes: ByteArray, mimeType: String = "image/jpeg") {
        if (!connected || jpegBytes.isEmpty()) return
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().put(JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", b64)
                }))
            })
        }
        ws?.send(msg.toString())
    }

    /**
     * Send a text turn. [turnComplete] true means "my turn is done,
     * generate a response now"; false keeps the turn open for more
     * input.
     */
    fun sendText(text: String, turnComplete: Boolean = true) {
        if (!connected || text.isEmpty()) return
        val msg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }))
                put("turnComplete", turnComplete)
            })
        }
        ws?.send(msg.toString())
    }

    /**
     * Respond to a [GeminiEvent.ToolCall] with its result. The response
     * is a JSON object the calling function would return.
     */
    fun sendToolResponse(callId: String, result: JSONObject) {
        if (!connected) return
        val msg = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().put(JSONObject().apply {
                    put("id", callId)
                    put("response", result)
                }))
            })
        }
        ws?.send(msg.toString())
    }

    private fun defaultSetup() = GeminiLiveSetup(model = model)

    // ──────────────────────────────────────────────────────── listener

    private inner class Listener(
        private val setup: GeminiLiveSetup,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            _events.tryEmit(GeminiEvent.Open)
            webSocket.send(buildSetup(setup).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleServer(JSONObject(text))
            } catch (t: Throwable) {
                Log.w(TAG, "failed to parse server message", t)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Gemini Live protocol is JSON-in-text frames. Binary frames
            // are unusual but decodable as UTF-8 to the same shape.
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closing code=$code reason=$reason")
            connected = false
            _events.tryEmit(GeminiEvent.Closed(reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure", t)
            connected = false
            _events.tryEmit(GeminiEvent.Error(t, response?.message ?: "ws failure"))
            _events.tryEmit(GeminiEvent.Closed("failure"))
        }
    }

    // ──────────────────────────────────────────────────────── decoding

    private fun handleServer(json: JSONObject) {
        if (json.has("setupComplete")) {
            _events.tryEmit(GeminiEvent.SetupComplete)
            return
        }

        json.optJSONObject("toolCall")?.let { tc ->
            val calls = tc.optJSONArray("functionCalls") ?: return@let
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                _events.tryEmit(GeminiEvent.ToolCall(
                    name = call.optString("name", ""),
                    args = call.optJSONObject("args") ?: JSONObject(),
                    callId = call.optString("id", ""),
                ))
            }
            return
        }

        val sc = json.optJSONObject("serverContent")
        if (sc != null) {
            sc.optJSONObject("modelTurn")?.let { mt ->
                val parts = mt.optJSONArray("parts") ?: return@let
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val txt = part.optString("text", "")
                    if (txt.isNotEmpty()) {
                        _events.tryEmit(GeminiEvent.TextDelta(txt))
                    }
                    part.optJSONObject("inlineData")?.let { inline ->
                        val mime = inline.optString("mimeType", "")
                        val data = inline.optString("data", "")
                        if (mime.startsWith("audio/") && data.isNotEmpty()) {
                            val pcm = try {
                                Base64.decode(data, Base64.DEFAULT)
                            } catch (_: Throwable) { null }
                            if (pcm != null) {
                                _events.tryEmit(GeminiEvent.AudioDelta(pcm, GEMINI_AUDIO_SAMPLE_RATE))
                            }
                        }
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.let { t ->
                _events.tryEmit(GeminiEvent.InputTranscription(
                    text = t.optString("text", ""),
                    isFinal = sc.optBoolean("turnComplete", false),
                ))
            }
            sc.optJSONObject("outputTranscription")?.let { t ->
                _events.tryEmit(GeminiEvent.OutputTranscription(
                    text = t.optString("text", ""),
                    isFinal = sc.optBoolean("turnComplete", false),
                ))
            }
            if (sc.optBoolean("turnComplete", false)) {
                _events.tryEmit(GeminiEvent.TurnComplete(
                    interrupted = sc.optBoolean("interrupted", false),
                ))
            }
            return
        }

        // Unknown — bubble up raw so callers can inspect during bring-up.
        _events.tryEmit(GeminiEvent.Raw(json))
    }

    // ──────────────────────────────────────────────────────── setup payload

    private fun buildSetup(s: GeminiLiveSetup): JSONObject = JSONObject().apply {
        put("setup", JSONObject().apply {
            put("model", if (s.model.startsWith("models/")) s.model else "models/${s.model}")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    s.responseModalities.forEach { put(it.wire) }
                })
                if (GeminiModality.AUDIO in s.responseModalities) {
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", s.voiceName)
                            })
                        })
                    })
                }
            })
            s.systemInstruction?.takeIf { it.isNotBlank() }?.let { sys ->
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", sys)))
                })
            }
            if (s.enableInputTranscription)  put("inputAudioTranscription", JSONObject())
            if (s.enableOutputTranscription) put("outputAudioTranscription", JSONObject())
        })
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** Gemini Live TTS output is always 24 kHz mono PCM16. */
        const val GEMINI_AUDIO_SAMPLE_RATE = 24_000
    }
}
