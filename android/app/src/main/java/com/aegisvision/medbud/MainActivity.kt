package com.aegisvision.medbud

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aegisvision.medbud.databinding.ActivityMainBinding
import com.aegisvision.medbud.perception.PerceptionDebugActivity
import com.aegisvision.medbud.perception.PerceptionHolder
import com.aegisvision.medbud.report.HandoffReportActivity
import com.aegisvision.medbud.report.IncidentHolder
import com.aegisvision.medbud.report.IncidentStatus
import com.aegisvision.medbud.voice.VoiceHolder
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SIGNALING_URL = "ws://192.168.4.58:8080"
        private const val MENU_PERCEPTION = 1

        // Only actually runtime-dangerous permissions go through the launcher.
        // BLUETOOTH is legacy (maxSdkVersion=30 in manifest), INTERNET is a
        // normal install-time permission — requesting either on API 31+ makes
        // the launcher report them as denied even when the manifest grants
        // them, which tripped the `allGranted` check here.
        private val REQUIRED_PERMS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var eglBase: EglBase
    private lateinit var webRtcClient: WebRTCClient
    private lateinit var videoStreamManager: VideoStreamManager
    private lateinit var glasses: GlassesConnectionManager
    private lateinit var signaling: SignalingClient

    private var datInitialized = false
    private var streaming = false
    private var hasActiveDevice = false
    private var isRegistered = false

    // Android runtime permission request (Bluetooth / Camera / Internet).
    // Must be registered before onStart; the callback initializes DAT.
    private val permissionCheckLauncher = registerForActivityResult(RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Grant Bluetooth + Camera permissions to continue", Toast.LENGTH_LONG).show()
            binding.statusText.text = "Permissions denied"
        }
    }

    // Meta-side camera permission ("may this app access glasses camera?") —
    // uses DAT's own Contract which opens the Meta AI app for consent.
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        if (status == PermissionStatus.Granted) {
            Log.i(TAG, "Wearables CAMERA permission granted, starting stream")
            doStartStream()
        } else {
            binding.statusText.text = "Meta camera permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize DAT first so any subsequent Wearables.* call is safe.
        // Safe to call unconditionally; internally idempotent.
        Wearables.initialize(this)
        datInitialized = true

        initPipeline()
        observeWearablesState()

        // Phase 3.1: start the voice delivery engine. Idempotent; safe to
        // call every onCreate (e.g. after rotation).
        VoiceHolder.ensureStarted(this)

        // Phase 6: incident logger + bridge. Attaches to the same
        // PerceptionRepository + GuidanceLoopEngine singletons.
        VoiceHolder.guidance?.let { g ->
            IncidentHolder.ensureStarted(PerceptionHolder.repository, g)
        }

        binding.connectButton.setOnClickListener {
            // Opens the Meta AI registration / consent UI.
            Wearables.startRegistration(this)
        }
        binding.streamButton.setOnClickListener {
            if (streaming) stopStream() else checkPermThenStartStream()
        }
        binding.askAiButton.setOnClickListener {
            VoiceHolder.guidance?.triggerManualWake()
        }
        binding.logsButton.setOnClickListener {
            startActivity(Intent(this, PerceptionDebugActivity::class.java))
        }
        binding.reportButton.setOnClickListener {
            startActivity(Intent(this, HandoffReportActivity::class.java))
        }

        // Pipe VLM detections into the on-stream overlay.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PerceptionHolder.repository.detections.collect { list ->
                    binding.detectionOverlay.setDetections(list)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-requesting is cheap if already granted — system no-ops.
        permissionCheckLauncher.launch(REQUIRED_PERMS)
    }

    private fun initPipeline() {
        eglBase = EglBase.create()
        binding.previewRenderer.init(eglBase.eglBaseContext, null)
        binding.previewRenderer.setEnableHardwareScaler(true)
        binding.previewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        // Composite the GL surface above the window so the underlying
        // (opaque) activity background doesn't paint over it.
        binding.previewRenderer.setZOrderMediaOverlay(true)

        webRtcClient = WebRTCClient(
            appContext = applicationContext,
            eglBase = eglBase,
            onIceCandidate = { cand -> signaling.sendIceCandidate(cand) },
            onConnectionStateChanged = { state ->
                runOnUiThread { binding.statusText.text = "WebRTC: $state" }
            }
        )
        webRtcClient.initPeerConnectionFactory()
        webRtcClient.createPeerConnection()

        videoStreamManager = VideoStreamManager(
            webRtc = webRtcClient,
            preview = binding.previewRenderer,
            onPerceptionJpeg = { jpeg -> PerceptionHolder.repository.submitFrame(jpeg) },
            perceptionIntervalMs = 1000L,
        )

        glasses = GlassesConnectionManager(
            onVideoFrame = { frame -> videoStreamManager.onCameraFrame(frame) },
            onStreamState = { s ->
                runOnUiThread { binding.statusText.text = "Stream: $s" }
                // Start the continuous mic ONLY after DAT is actively streaming,
                // so AudioRecord doesn't renegotiate BT audio routing during the
                // DAT session handshake. Stop it the moment the stream closes.
                when (s) {
                    StreamSessionState.STREAMING -> {
                        // Stream is actually flowing now — only NOW activate
                        // the guidance loop, start the always-on listener,
                        // and begin the incident session. Doing any of this
                        // earlier has been observed to race the DAT audio
                        // profile handshake and cause the glasses to drop
                        // the session with an orange-LED blink.
                        VoiceHolder.guidance?.setActive(true)
                        IncidentHolder.beginIncident()
                        VoiceHolder.startListening()
                    }
                    StreamSessionState.CLOSED -> {
                        VoiceHolder.stopListening()
                        runOnUiThread { resetStreamButton() }
                    }
                    else -> {}
                }
            },
            onError = { msg ->
                Log.e(TAG, msg)
                runOnUiThread { binding.statusText.text = msg }
            }
        )

        signaling = SignalingClient(
            url = SIGNALING_URL,
            role = "phone",
            onOfferNeeded = {
                webRtcClient.createOffer { offer -> signaling.sendOffer(offer) }
            },
            onRemoteAnswer = { sdp -> webRtcClient.setRemoteAnswer(sdp) },
            onRemoteIce = { cand -> webRtcClient.addRemoteIce(cand) }
        )
        signaling.connect()
    }

    private fun observeWearablesState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Wearables.registrationState.collect { rs ->
                        isRegistered = rs is RegistrationState.Registered
                        updateStatus(reg = rs::class.simpleName ?: "?")
                        updateButtons()
                    }
                }
                launch {
                    glasses.deviceSelector.activeDeviceFlow().collect { device ->
                        hasActiveDevice = device != null
                        updateStatus()
                        updateButtons()
                    }
                }
            }
        }
    }

    private fun checkPermThenStartStream() {
        lifecycleScope.launch {
            val result = Wearables.checkPermissionStatus(Permission.CAMERA)
            result.onSuccess { status ->
                if (status == PermissionStatus.Granted) {
                    doStartStream()
                } else {
                    // Opens Meta AI consent flow. doStartStream runs in the launcher callback.
                    wearablesPermissionLauncher.launch(Permission.CAMERA)
                }
            }.onFailure { err, _ ->
                binding.statusText.text = "Perm check failed: ${err.description}"
            }
        }
    }

    private fun doStartStream() {
        Log.i(TAG, "doStartStream() — calling glasses.startStream()")
        // Fresh session: wipe guidance state from any previous run.
        // IMPORTANT: do NOT activate the guidance loop, start the mic,
        // or begin the incident session here — that all happens in the
        // STREAMING state callback so nothing touches audio/BT during
        // the DAT handshake.
        VoiceHolder.guidance?.reset()
        glasses.startStream()
        // Kick off an offer in case the viewer is already connected.
        webRtcClient.createOffer { offer -> signaling.sendOffer(offer) }
        streaming = true
        binding.streamButton.text = getString(R.string.btn_stop_stream)
        binding.statusDot.setBackgroundResource(R.drawable.status_dot_live)
    }

    private fun stopStream() {
        // Kill audio FIRST — interrupt any in-flight TTS and block any
        // pending cycles from producing audio after the stream is down.
        VoiceHolder.guidance?.setActive(false)
        // Phase 6: finalize the session and produce a HandoffReport from
        // the current snapshots. Status: HANDED_OFF if guidance escalated
        // to handoff mode, otherwise ENDED.
        val guidance = VoiceHolder.guidance
        if (guidance != null) {
            val repo = PerceptionHolder.repository
            val handedOff = guidance.state.value.loopStatus.name == "HANDOFF"
            IncidentHolder.endIncident(
                status = if (handedOff) IncidentStatus.HANDED_OFF else IncidentStatus.ENDED,
                perception = repo.state.value,
                decision = repo.decisionState.value,
                plan = repo.actionPlanState.value,
                clarification = repo.clarificationState.value,
                guidance = guidance.state.value,
                note = if (handedOff) "handoff escalation" else "user stopped stream",
            )
        }
        glasses.stopStream()
        VoiceHolder.stopListening()
        VoiceHolder.guidance?.reset()
        resetStreamButton()
    }

    private fun resetStreamButton() {
        streaming = false
        binding.streamButton.text = getString(R.string.btn_start_stream)
        binding.statusDot.setBackgroundResource(R.drawable.status_dot_idle)
    }

    private fun updateStatus(reg: String? = null) {
        val regPart = reg?.let { "reg=$it" } ?: if (isRegistered) "reg=Registered" else "reg=?"
        val devPart = if (hasActiveDevice) "device=yes" else "device=no"
        runOnUiThread { binding.statusText.text = "$regPart  $devPart" }
    }

    private fun updateButtons() {
        runOnUiThread {
            binding.streamButton.isEnabled = isRegistered && hasActiveDevice && datInitialized
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_PERCEPTION, 0, "Perception")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_PERCEPTION) {
            startActivity(Intent(this, PerceptionDebugActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::glasses.isInitialized) glasses.dispose()
        if (::webRtcClient.isInitialized) webRtcClient.dispose()
        if (::signaling.isInitialized) signaling.close()
        if (::binding.isInitialized) binding.previewRenderer.release()
        if (::eglBase.isInitialized) eglBase.release()
    }
}
