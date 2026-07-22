package com.example.prisonconnect

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.prisonconnect.databinding.ActivityVideoCallBinding
import com.example.prisonconnect.databinding.DialogCallSummaryBinding
import com.example.prisonconnect.repository.DbService
import com.example.prisonconnect.webrtc.CallDiagnosticHelper
import com.example.prisonconnect.webrtc.CallViewModel
import com.example.prisonconnect.webrtc.WebRtcManager
import com.example.prisonconnect.webrtc.SmsController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video call activity for video calls.
 *
 * Manages the full lifecycle of a video call including:
 * - Permission checking and diagnostics
 * - SMS link sending
 * - WebRTC peer connection and signaling
 * - Local and remote video rendering
 * - Call timer and balance tracking
 * - Recording service management
 * - Call summary dialog
 * - Picture-in-picture style local video overlay with drag support
 */
class VideoCallActivity : AppCompatActivity(), WebRtcManager.WebRtcListener {

    private val TAG = "VideoCallActivity"

    private lateinit var binding: ActivityVideoCallBinding
    private val viewModel: CallViewModel by viewModels()

    // Configuration
    private var roomId: String = ""
    private var inmateId: String = ""
    private var inmateName: String = ""
    private var contactPhone: String = ""
    private var jailName: String = ""
    private var initialBalance: Long = 0L
    private var roomOtp: String = ""
    private var roomToken: String = ""
    private var isOtpSent: Boolean = false

    // WebRTC & Signaling
    private lateinit var webRtcManager: WebRtcManager
    private lateinit var smsController: SmsController
    private val eglBase = EglBase.create()

    private var isCallStarted = AtomicBoolean(false)
    private var isOfferSent = AtomicBoolean(false)
    private var isRemoteDescriptionSet = false
    private val remoteCandidatesQueue = mutableListOf<IceCandidate>()

    // Services & Helpers
    private var recordingService: SecureHardwareRecordingService? = null
    private var isServiceBound = false
    private var diagnosticHelper: CallDiagnosticHelper? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SecureHardwareRecordingService.LocalBinder
            recordingService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startCallFlow()
        } else {
            updateLobbyStatus("❌ Permissions denied. Cannot proceed.")
        }
    }

    // Logging wrapper for consistency
    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.d(TAG, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        extractArguments()
        setupUI()

        smsController = SmsController()
        webRtcManager = WebRtcManager(this, eglBase, this)
        diagnosticHelper = CallDiagnosticHelper(this)

        viewModel.initCall(inmateId, roomId, initialBalance)
        viewModel.startSignaling(roomId)

        observeViewModel()
        checkPermissionsAndStart()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmExit()
            }
        })
    }

    private fun extractArguments() {
        roomId = intent.getStringExtra("room_id") ?: ""
        inmateId = intent.getStringExtra("user_id") ?: ""
        inmateName = intent.getStringExtra("inmate_name") ?: "Inmate"
        contactPhone = intent.getStringExtra("phone_number") ?: ""
        jailName = intent.getStringExtra("jail_name") ?: ""
        initialBalance = intent.getLongExtra("initial_balance", 0L)
        log("Extracted arguments: room=$roomId, inmate=$inmateId, phone=$contactPhone")
    }

    private fun setupUI() {
        binding.tvVideoName.text = inmateName

        binding.btnVideoHangup.setOnClickListener { confirmExit() }
        binding.btnVideoMic.setOnClickListener { toggleMic() }
        binding.btnVideoSwitch.setOnClickListener { webRtcManager.switchCamera() }

        setupOverlayLogic()
        setupVideoDrag()
    }

    private fun checkPermissionsAndStart() {
        val result = diagnosticHelper?.performFullDiagnostic(isVideo = true)
        when (result) {
            is CallDiagnosticHelper.DiagnosticResult.Success -> startCallFlow()
            is CallDiagnosticHelper.DiagnosticResult.PermissionMissing -> {
                permissionLauncher.launch(result.permissions.toTypedArray())
            }
            else -> {
                updateLobbyStatus("⚠️ Hardware diagnostic failed.")
            }
        }
    }

    private fun startCallFlow() {
        lifecycleScope.launch(Dispatchers.Default) {
            webRtcManager.initialize()
            withContext(Dispatchers.Main) {
                sendCallLink()
            }
        }
    }

    private suspend fun createRoomRecord(): Boolean {
        if (roomId.isBlank() || inmateId.isBlank()) return false
        return try {
            roomOtp = (100000..999999).random().toString()
            roomToken = "sess_${UUID.randomUUID().toString().replace("-", "")}"
            val roomData: Map<String, Any> = mapOf(
                "room_id" to roomId,
                "kiosk_id" to "KIOSK_001",
                "inmate_id" to inmateId,
                "call_type" to "VIDEO",
                "room_status" to "WAITING",
                "receiver_phone" to contactPhone,
                "otp" to roomOtp,
                "token" to roomToken,
                "webrtc_signaling" to mapOf(
                    "offer" to null,
                    "answer" to null,
                    "iceCandidates" to emptyList<Map<String, Any>>()
                )
            )
            DbService.insertRaw("call_rooms", roomData)
            log("Room created in database: $roomId")
            true
        } catch (e: Exception) {
            log("Failed to create room record", e)
            false
        }
    }

    private fun sendCallLink() {
        lifecycleScope.launch {
            val roomCreated = createRoomRecord()
            if (!roomCreated) {
                updateLobbyStatus("❌ Failed to initialize call room. Please try again.")
                return@launch
            }
            val smsLink = "https://prisonconnect-call.rf.gd/index.html?room=$roomId&token=$roomToken"
            val linkMessage = "PrisonConnect: Video call from $inmateName at $jailName. Join: $smsLink"
            smsController.sendSmsViaSupabase(contactPhone, linkMessage)
            updateLobbyStatus("📱 Call link sent to $contactPhone")
            log("Call link sent to $contactPhone")
        }
    }

    private suspend fun sendOtpSms() {
        if (roomOtp.isBlank() || isOtpSent) return
        isOtpSent = true
        try {
            val otpMessage = "PrisonConnect: Your access code is: $roomOtp"
            val result = smsController.sendSmsViaSupabase(contactPhone, otpMessage)
            if (!result.isSuccess) {
                isOtpSent = false
                log("Failed to send OTP SMS", result.exceptionOrNull())
            } else {
                log("OTP SMS sent to $contactPhone")
            }
        } catch (e: Exception) {
            log("Failed to send OTP SMS", e)
            isOtpSent = false
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.roomStatus.collect { status ->
                        handleRoomStatus(status)
                    }
                }
                launch {
                    viewModel.elapsedSeconds.collect { seconds ->
                        binding.tvVideoDuration.text = formatSeconds(seconds)
                    }
                }
                launch {
                    viewModel.remainingBalance.collect { balance ->
                        binding.tvVideoBalance.text = "Bal: ${formatSeconds(balance)}"
                        if (balance <= 0) teardownAndExit()
                    }
                }
                launch {
                    viewModel.signalingEvents.collect { event ->
                        handleSignalingEvent(event)
                    }
                }
            }
        }
    }

    private fun handleRoomStatus(status: String) {
        log("Room status: $status")
        when (status) {
            "WAITING" -> updateLobbyStatus("Waiting for terminal...")
            "OTP_SENT" -> {
                updateLobbyStatus("✅ Link opened! Sending code...")
                // Fallback: send OTP if not already sent
                if (!isOtpSent) {
                    lifecycleScope.launch {
                        sendOtpSms()
                    }
                }
            }
            "ACTIVE" -> {
                updateLobbyStatus("🔐 Access verified! Connecting...")
                activateWebRtc()
            }
            "CONNECTED" -> showCallUi()
            "DISCONNECTED", "TAMPER_KILLED" -> teardownAndExit()
        }
    }

    private fun handleSignalingEvent(event: CallViewModel.SignalingEvent) {
        when (event) {
            is CallViewModel.SignalingEvent.SiteOpened -> {
                updateLobbyStatus("✅ Link opened!")
                log("Site opened event received, sending OTP...")
                // Update status to OTP_SENT and send OTP SMS
                lifecycleScope.launch {
                    try {
                        DbService.updateFieldsByColumn(
                            "call_rooms",
                            "room_id",
                            roomId,
                            mapOf("room_status" to "OTP_SENT")
                        )
                        sendOtpSms()
                    } catch (e: Exception) {
                        log("Failed to send OTP after site opened", e)
                    }
                }
            }
            is CallViewModel.SignalingEvent.OtpVerified -> {
                updateLobbyStatus("🔐 Access verified!")
                activateWebRtc()
            }
            is CallViewModel.SignalingEvent.WebReady -> createOffer()
            is CallViewModel.SignalingEvent.AnswerReceived -> setRemoteDescription(event.sdp, event.type)
            is CallViewModel.SignalingEvent.CandidatesReceived -> handleRemoteCandidates(event.candidates)
            is CallViewModel.SignalingEvent.HangupReceived -> teardownAndExit()
        }
    }

    private fun activateWebRtc() {
        if (!isCallStarted.compareAndSet(false, true)) return
        log("Activating WebRTC...")

        runOnUiThread {
            try {
                // 1. Initialize EGL Context on Local View FIRST
                binding.localView.init(eglBase.eglBaseContext, null)
                binding.localView.setMirror(true)
                binding.localView.setEnableHardwareScaler(true)

                // Keep localView strictly on top of full-screen remoteView
                binding.localView.setZOrderMediaOverlay(true)
                binding.localView.setZOrderOnTop(true)
                binding.localView.visibility = View.VISIBLE

                // 2. Initialize Remote View
                binding.remoteView.init(eglBase.eglBaseContext, null)
                binding.remoteView.setEnableHardwareScaler(true)
                binding.remoteView.setMirror(false)

                // 3. Setup PeerConnection and Pass Local View as VideoSink
                webRtcManager.setupPeerConnection(isVideo = true)

                // Yahan binding.localView direct VideoSink ki tarah pass hoga
                webRtcManager.startLocalStream(binding.localView, isVideo = true)

                startRecordingService()
                showCallUi()
            } catch (e: Exception) {
                log("Failed to activate WebRTC", e)
                isCallStarted.set(false)
            }
        }
    }

    private fun createOffer() {
        val pc = webRtcManager.currentPeerConnection ?: return
        if (isOfferSent.compareAndSet(false, true)) {
            // Wait for tracks to be ready before creating offer
            // This prevents one-way media issues
            lifecycleScope.launch {
                var waitTime = 0
                while (!webRtcManager.isTracksReady && waitTime < 3000) {
                    delay(100)
                    waitTime += 100
                }
                if (!webRtcManager.isTracksReady) {
                    log("Warning: tracks not ready after 3s, creating offer anyway")
                }
                createOfferInternal(pc)
            }
            return
        }
    }

    private fun createOfferInternal(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        viewModel.sendOffer(sdp.description, sdp.type.canonicalForm())
                        log("Offer created and sent successfully")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                log("Failed to create offer", null)
                isOfferSent.set(false)
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun setRemoteDescription(sdp: String, type: String) {
        val pc = webRtcManager.currentPeerConnection ?: return
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type),
            sdp
        )
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                synchronized(remoteCandidatesQueue) {
                    remoteCandidatesQueue.forEach { pc.addIceCandidate(it) }
                    remoteCandidatesQueue.clear()
                }
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sessionDescription)
    }

    private fun handleRemoteCandidates(candidates: List<JsonObject>) {
        candidates.forEach { data ->
            val sdpMid = data["sdpMid"]?.jsonPrimitive?.content
            val sdpMLineIndex = data["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val sdp = data["candidate"]?.jsonPrimitive?.content
                ?: data["sdp"]?.jsonPrimitive?.content
            if (sdp != null) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                if (isRemoteDescriptionSet) {
                    webRtcManager.currentPeerConnection?.addIceCandidate(candidate)
                } else {
                    synchronized(remoteCandidatesQueue) {
                        remoteCandidatesQueue.add(candidate)
                    }
                }
            }
        }
    }

    private fun toggleMic() {
        val enabled = !binding.btnVideoMic.isActivated
        webRtcManager.setAudioEnabled(enabled)
        binding.btnVideoMic.isActivated = enabled

        if (enabled) {
            binding.btnVideoMic.setIconResource(R.drawable.ic_mic)
            binding.btnVideoMic.setIconTintResource(R.color.white)
        } else {
            binding.btnVideoMic.setIconResource(R.drawable.ic_mic_off)
            binding.btnVideoMic.setIconTintResource(R.color.danger)
        }
    }

    private fun updateLobbyStatus(message: String) {
        binding.tvLobbyStatus.text = message
    }

    private fun showCallUi() {
        runOnUiThread {
            if (binding.lobbyContainer.visibility != View.GONE) {
                binding.lobbyContainer.visibility = View.GONE
                binding.videoCallUi.visibility = View.VISIBLE
                webRtcManager.setSpeakerphoneOn(true)
                viewModel.startCallTimer()
            }
        }
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle("End Call")
            .setMessage("Are you sure you want to end this video call?")
            .setPositiveButton("End Call") { _, _ -> teardownAndExit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun teardownAndExit() {
        viewModel.endCall()
        releaseResources()
        showSummaryDialog()
    }

    private fun releaseResources() {
        stopRecordingService()
        webRtcManager.cleanup()
        try {
            eglBase.release()
        } catch (e: Exception) {
            log("Error releasing EGL base", e)
        }
    }

    private fun showSummaryDialog() {
        if (isFinishing) return
        val dialogBinding = DialogCallSummaryBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.tvSummaryPhone.text = contactPhone
        dialogBinding.tvSummaryDuration.text = formatDuration(viewModel.elapsedSeconds.value)
        dialogBinding.tvSummaryBalance.text = formatDuration(
            viewModel.remainingBalance.value.coerceAtLeast(0)
        )
        dialogBinding.btnSummaryOk.setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.show()
    }

    private fun startRecordingService() {
        val intent = Intent(this, SecureHardwareRecordingService::class.java).apply {
            putExtra("ROOM_ID", roomId)
            putExtra("KIOSK_ID", "KIOSK_001")
            putExtra("SESSION_ID", UUID.randomUUID().toString())
            putExtra("INMATE_NAME", inmateName)
            putExtra("RECEIVER_NAME", contactPhone)
        }
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun stopRecordingService() {
        if (isServiceBound) {
            recordingService?.stopAndUpload()
            unbindService(serviceConnection)
            isServiceBound = false
        }
        stopService(Intent(this, SecureHardwareRecordingService::class.java))
    }

    // --- WebRtcListener Implementation ---

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        viewModel.queueLocalCandidate(candidate)
    }

    override fun onIceConnected() {
        log("ICE Connection Established successfully!")
        runOnUiThread {
            viewModel.updateRoomStatus("CONNECTED")
            showCallUi()
        }
    }

    override fun onIceConnectionFailed() {
        log("ICE Connection Failed")
    }

    override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
        log("Remote video track received, attaching to sink...")
        runOnUiThread {
            try {
                videoTrack.setEnabled(true)
                videoTrack.addSink(binding.remoteView)
                binding.remoteView.requestLayout()
            } catch (e: Exception) {
                log("Error attaching remote video sink", e)
            }
        }
    }

    // --- UI Helpers ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVideoDrag() {
        var dX = 0f
        var dY = 0f
        binding.localView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                }
            }
            true
        }
    }

    private fun setupOverlayLogic() {
        binding.videoClickOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) toggleOverlays()
            true
        }
    }

    private fun toggleOverlays() {
        val visible = binding.videoHeader.visibility == View.VISIBLE
        binding.videoHeader.visibility = if (visible) View.GONE else View.VISIBLE
        binding.videoFooter.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes min $seconds sec"
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }
}