package com.aegisvision.medbud

import android.content.Context
import org.webrtc.CapturerObserver
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel

class WebRTCClient(
    private val appContext: Context,
    private val eglBase: EglBase,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChanged: (String) -> Unit
) {
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private lateinit var videoSource: VideoSource
    private lateinit var videoTrack: VideoTrack
    private lateinit var capturer: ExternalVideoCapturer

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { onIceCandidate.invoke(candidate) }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onConnectionStateChanged(newState.name)
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        // isScreencast = true: accept arbitrary frame sizes (no adapter).
        // Glasses deliver 504×896 I420, which the default adapter was dropping.
        videoSource = factory.createVideoSource(/* isScreencast = */ true)
        capturer = ExternalVideoCapturer()
        capturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            appContext,
            videoSource.capturerObserver
        )
        capturer.startCapture(0, 0, 0)

        videoTrack = factory.createVideoTrack("POV_V0", videoSource)
        peerConnection?.addTrack(videoTrack, listOf("POV_STREAM"))

        // Optional mic audio track — enable if you want phone mic audio alongside.
        // val audioSource = factory.createAudioSource(MediaConstraints())
        // val audioTrack = factory.createAudioTrack("POV_A0", audioSource)
        // peerConnection?.addTrack(audioTrack, listOf("POV_STREAM"))
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        videoTrack.addSink(renderer)
    }

    fun pushVideoFrame(frame: VideoFrame) {
        capturer.pushFrame(frame)
    }

    fun createOffer(onLocalSdp: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                onLocalSdp(sdp)
            }
        }, constraints)
    }

    fun setRemoteAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addRemoteIce(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        try { capturer.stopCapture() } catch (_: Throwable) {}
    }

    fun dispose() {
        close()
        try { capturer.dispose() } catch (_: Throwable) {}
        peerConnection?.close()
        peerConnection = null
        if (::videoSource.isInitialized) videoSource.dispose()
        if (::factory.isInitialized) factory.dispose()
    }

    /** Minimal [VideoCapturer] that forwards manually-pushed frames. */
    class ExternalVideoCapturer : VideoCapturer {
        private var observer: CapturerObserver? = null
        private var started = false

        override fun initialize(
            helper: SurfaceTextureHelper?, ctx: Context?, obs: CapturerObserver?
        ) {
            this.observer = obs
        }

        override fun startCapture(w: Int, h: Int, fps: Int) {
            started = true
            observer?.onCapturerStarted(true)
        }

        override fun stopCapture() {
            started = false
            observer?.onCapturerStopped()
        }

        override fun changeCaptureFormat(w: Int, h: Int, fps: Int) {}
        override fun dispose() { started = false; observer = null }
        override fun isScreencast() = false

        fun pushFrame(frame: VideoFrame) {
            if (started) observer?.onFrameCaptured(frame)
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
