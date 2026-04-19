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
                    StreamSessionState.STREAMING -> VoiceHolder.startListening()
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
        // Fresh session: wipe guidance state so we don't repeat whatever
        // step the last run ended on.
        VoiceHolder.guidance?.reset()
        glasses.startStream()
        // Kick off an offer in case the viewer is already connected.
        webRtcClient.createOffer { offer -> signaling.sendOffer(offer) }
        streaming = true
        binding.streamButton.text = "Stop Stream"
    }

    private fun stopStream() {
        glasses.stopStream()
        VoiceHolder.stopListening()
        VoiceHolder.guidance?.reset()
        resetStreamButton()
    }

    private fun resetStreamButton() {
        streaming = false
        binding.streamButton.text = "Start Stream"
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
