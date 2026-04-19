package com.aegisvision.medbud

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.URI

/**
 * Thin WebSocket signaling client. Sends offer + ICE, receives answer + ICE.
 * The server notifies us via `viewer-ready` when a browser joins.
 */
class SignalingClient(
    url: String,
    role: String, // "phone" or "viewer"
    private val onOfferNeeded: () -> Unit,
    private val onRemoteAnswer: (SessionDescription) -> Unit,
    private val onRemoteIce: (IceCandidate) -> Unit
) {
    private val socket = object : WebSocketClient(URI("$url?role=$role")) {
        override fun onOpen(handshakedata: ServerHandshake?) { Log.i(TAG, "WS open") }
        override fun onMessage(message: String) { handle(message) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Log.w(TAG, "WS closed: $reason")
        }
        override fun onError(ex: Exception?) { Log.e(TAG, "WS error", ex) }
    }

    fun connect() = socket.connect()
    fun close() = socket.close()

    fun sendOffer(sdp: SessionDescription) {
        send(JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp.description)
        })
    }

    fun sendIceCandidate(c: IceCandidate) {
        send(JSONObject().apply {
            put("type", "ice")
            put("candidate", c.sdp)
            put("sdpMid", c.sdpMid)
            put("sdpMLineIndex", c.sdpMLineIndex)
        })
    }

    private fun send(obj: JSONObject) {
        if (socket.isOpen) socket.send(obj.toString())
    }

    private fun handle(msg: String) {
        val json = JSONObject(msg)
        when (json.optString("type")) {
            "viewer-ready" -> onOfferNeeded()
            "answer" -> onRemoteAnswer(
                SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
            )
            "ice" -> onRemoteIce(
                IceCandidate(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("candidate")
                )
            )
        }
    }

    companion object { private const val TAG = "SignalingClient" }
}
