package com.aegisvision.medbud

import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the DAT session + camera stream lifecycle. The Activity drives
 * permission + registration flow; this class takes over once a device is
 * available and hands raw [VideoFrame]s to [VideoStreamManager].
 */
class GlassesConnectionManager(
    private val onVideoFrame: (VideoFrame) -> Unit,
    private val onStreamState: (StreamSessionState) -> Unit,
    private val onError: (String) -> Unit
) {
    val deviceSelector: DeviceSelector = AutoDeviceSelector()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: Session? = null
    private var stream: Stream? = null
    private var sessionStateJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var videoJob: Job? = null

    /**
     * Create a session for the currently-active device and, once the session
     * reaches [DeviceSessionState.STARTED], add a medium-quality 24 FPS camera
     * stream. Frames flow through [onVideoFrame].
     */
    fun startStream() {
        stopStream()
        Log.i(TAG, "startStream() entry; creating session")

        Wearables.createSession(deviceSelector)
            .onSuccess { created ->
                Log.i(TAG, "session created, calling start()")
                session = created
                created.start()
                sessionStateJob = scope.launch {
                    created.state.collect { s ->
                        Log.i(TAG, "session state=$s")
                        if (s == DeviceSessionState.STARTED) addStream(created)
                    }
                }
            }
            .onFailure { err, _ ->
                Log.e(TAG, "createSession failed: ${err.description}")
                onError("createSession failed: ${err.description}")
            }
    }

    private fun addStream(currentSession: Session) {
        Log.i(TAG, "session STARTED, adding camera stream")
        currentSession.addStream(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24)
        )
            .onSuccess { added ->
                Log.i(TAG, "stream added, calling start()")
                stream = added

                videoJob = scope.launch {
                    added.videoStream.collect { frame -> onVideoFrame(frame) }
                }
                streamStateJob = scope.launch {
                    added.state.collect { state ->
                        Log.i(TAG, "stream state=$state")
                        onStreamState(state)
                    }
                }
                streamErrorJob = scope.launch {
                    added.errorStream.collect { e ->
                        Log.e(TAG, "stream errorStream: ${e.description}")
                        onError("stream error: ${e.description}")
                    }
                }

                added.start()
            }
            .onFailure { err, _ ->
                Log.e(TAG, "addStream failed: ${err.description}")
                onError("addStream failed: ${err.description}")
            }
    }

    fun stopStream() {
        videoJob?.cancel(); videoJob = null
        streamStateJob?.cancel(); streamStateJob = null
        streamErrorJob?.cancel(); streamErrorJob = null
        sessionStateJob?.cancel(); sessionStateJob = null
        try { stream?.stop() } catch (t: Throwable) { Log.w(TAG, "stream.stop()", t) }
        stream = null
        try { session?.stop() } catch (t: Throwable) { Log.w(TAG, "session.stop()", t) }
        session = null
    }

    fun dispose() {
        stopStream()
        scope.cancel()
    }

    companion object { private const val TAG = "GlassesConnection" }
}
