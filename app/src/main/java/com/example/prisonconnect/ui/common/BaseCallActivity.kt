package com.example.prisonconnect.ui.common

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.example.prisonconnect.databinding.DialogCallSummaryBinding
import com.example.prisonconnect.data.remote.DbService
import com.example.prisonconnect.util.CallDiagnosticHelper
import com.example.prisonconnect.ui.call.CallViewModel
import com.example.prisonconnect.ui.call.SmsController
import com.example.prisonconnect.ui.call.WebRtcManager
import com.example.prisonconnect.service.SecureHardwareRecordingService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base Activity for both Audio and Video calls.
 * 
 * Centralizes WebRTC signaling, recording service management, and UI lifecycle.
 */
abstract class BaseCallActivity<VB : ViewBinding> : AppCompatActivity(), WebRtcManager.WebRtcListener {

    protected abstract val tag: String
    protected lateinit var binding: VB
    protected val viewModel: CallViewModel by viewModels()

    // Configuration
    protected var roomId: String = ""
    protected var inmateId: String = ""
    protected var inmateName: String = ""
    protected var contactPhone: String = ""
    protected var jailName: String = ""
    protected var initialBalance: Long = 0L
    protected var roomOtp: String = ""
    protected var roomToken: String = ""
    protected var isOtpSent: Boolean = false

    // WebRTC & Signaling
    protected lateinit var webRtcManager: WebRtcManager
    protected lateinit var smsController: SmsController
    protected val eglBase: EglBase = EglBase.create()

    protected var isCallStarted = AtomicBoolean(false)
    protected var isOfferSent = AtomicBoolean(false)
    protected var isRemoteDescriptionSet = false
    protected val remoteCandidatesQueue = mutableListOf<IceCandidate>()

    // Services
    protected var recordingService: SecureHardwareRecordingService? = null
    protected var isServiceBound = false
    protected var diagnosticHelper: CallDiagnosticHelper? = null

    protected abstract fun inflateBinding(): VB
    protected abstract fun setupCallUi()
    protected abstract fun onCallActivated()
    protected abstract val isVideoMode: Boolean

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SecureHardwareRecordingService.LocalBinder
            val boundService = binder.getService()
            recordingService = boundService
            isServiceBound = true
            
            lifecycleScope.launch(Dispatchers.Default) {
                webRtcManager.initialize(boundService, boundService)
            }
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

    protected fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.d(tag, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflateBinding()
        setContentView(binding.root)

        extractArguments()
        setupCallUi()

        smsController = SmsController(this)
        webRtcManager = WebRtcManager(this, eglBase, this)
        diagnosticHelper = CallDiagnosticHelper(this)

        viewModel.initCall(inmateId, roomId, initialBalance)
        viewModel.startSignaling(roomId)

        observeViewModel()
        
        // Start and Bind Recording Service immediately to get Audio Hooks
        startRecordingService()
        
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
        log("Extracted arguments: room=$roomId, inmate=$inmateId")
    }

    private fun checkPermissionsAndStart() {
        val result = diagnosticHelper?.performFullDiagnostic(isVideo = isVideoMode)
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
            // We now wait for the service to bind before initializing WebRTC
            // This is handled in onServiceConnected
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
                "call_type" to if (isVideoMode) "VIDEO" else "AUDIO",
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
                updateLobbyStatus("❌ Failed to initialize call room.")
                return@launch
            }
            val typeStr = if (isVideoMode) "Video" else "Audio"
            val smsLink = "https://prisonconnect-call.rf.gd/index.html?room=$roomId&token=$roomToken"
            val linkMessage = "PrisonConnect: $typeStr call from $inmateName at $jailName. Join: $smsLink"
            smsController.sendSmsViaSupabase(contactPhone, linkMessage)
            updateLobbyStatus("📱 Call link sent to $contactPhone")
        }
    }

    protected suspend fun sendOtpSms() {
        if (roomOtp.isBlank() || isOtpSent) return
        isOtpSent = true
        try {
            val otpMessage = "PrisonConnect: Your access code is: $roomOtp"
            val result = smsController.sendSmsViaSupabase(contactPhone, otpMessage)
            if (!result.isSuccess) {
                isOtpSent = false
                log("Failed to send OTP SMS", result.exceptionOrNull())
            }
        } catch (e: Exception) {
            log("Failed to send OTP SMS", e)
            isOtpSent = false
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.roomStatus.collect { handleRoomStatus(it) } }
                launch { viewModel.elapsedSeconds.collect { onTimerUpdated(it) } }
                launch { viewModel.remainingBalance.collect { onBalanceUpdated(it) } }
                launch { viewModel.signalingEvents.collect { handleSignalingEvent(it) } }
            }
        }
    }

    protected open fun onTimerUpdated(seconds: Long) {}
    protected open fun onBalanceUpdated(balance: Long) {
        if (balance <= 0) teardownAndExit()
    }

    /**
     * Suspends until the WebRTC manager is fully initialized.
     */
    protected suspend fun waitForWebRtcReady() {
        if (!webRtcManager.isReady.value) {
            log("Waiting for WebRTC to be ready...")
            webRtcManager.isReady.first { it }
            log("WebRTC is now ready")
        }
    }

    private fun handleRoomStatus(status: String) {
        log("Room status: $status")
        when (status) {
            "WAITING" -> updateLobbyStatus("Waiting for terminal...")
            "OTP_SENT" -> {
                updateLobbyStatus("✅ Link opened! Sending code...")
                if (!isOtpSent) lifecycleScope.launch { sendOtpSms() }
            }
            "ACTIVE" -> {
                updateLobbyStatus("🔐 Access verified! Connecting...")
                onCallActivated()
            }
            "CONNECTED" -> showCallUi()
            "DISCONNECTED", "TAMPER_KILLED" -> teardownAndExit()
        }
    }

    private fun handleSignalingEvent(event: CallViewModel.SignalingEvent) {
        when (event) {
            is CallViewModel.SignalingEvent.SiteOpened -> {
                updateLobbyStatus("✅ Link opened!")
                lifecycleScope.launch {
                    try {
                        DbService.updateFieldsByColumn("call_rooms", "room_id", roomId, mapOf("room_status" to "OTP_SENT"))
                        sendOtpSms()
                    } catch (e: Exception) { log("Failed site-opened update", e) }
                }
            }
            is CallViewModel.SignalingEvent.OtpVerified -> {
                updateLobbyStatus("🔐 Access verified!")
                onCallActivated()
            }
            is CallViewModel.SignalingEvent.WebReady -> {
                val iceState = webRtcManager.currentPeerConnection?.iceConnectionState()
                log("Web is ready. ICE State: $iceState")
                
                val isConnected = iceState == PeerConnection.IceConnectionState.CONNECTED || 
                                iceState == PeerConnection.IceConnectionState.COMPLETED
                
                if (isCallStarted.get()) {
                    if (isConnected) {
                        log("Peer already connected, ignoring redundant WebReady")
                    } else {
                        log("Peer signaled WebReady but not connected, triggering recovery")
                        resetNegotiation()
                    }
                }
            }
            is CallViewModel.SignalingEvent.AnswerReceived -> setRemoteDescription(event.sdp, event.type)
            is CallViewModel.SignalingEvent.CandidatesReceived -> handleRemoteCandidates(event.candidates)
            is CallViewModel.SignalingEvent.HangupReceived -> teardownAndExit()
        }
    }

    private fun resetNegotiation() {
        log("Resetting negotiation for Browser Refresh/Recovery")
        isOfferSent.set(false)
        isRemoteDescriptionSet = false
        synchronized(remoteCandidatesQueue) {
            remoteCandidatesQueue.clear()
        }
        createOffer()
    }

    protected fun createOffer(iceRestart: Boolean = false) {
        if (isOfferSent.compareAndSet(false, true)) {
            val pc = webRtcManager.currentPeerConnection ?: return
            createOfferInternal(pc, iceRestart)
        }
    }

    private fun createOfferInternal(pc: PeerConnection, iceRestart: Boolean) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoMode) "true" else "false"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        viewModel.sendOffer(sdp.description, sdp.type.canonicalForm())
                        log("Offer sent successfully")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                log("Failed to create offer")
                isOfferSent.set(false)
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun setRemoteDescription(sdp: String, type: String) {
        val pc = webRtcManager.currentPeerConnection ?: return
        val sessionDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
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
            val sdp = data["candidate"]?.jsonPrimitive?.content ?: data["sdp"]?.jsonPrimitive?.content
            if (sdp != null) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                if (isRemoteDescriptionSet) {
                    webRtcManager.currentPeerConnection?.addIceCandidate(candidate)
                } else {
                    synchronized(remoteCandidatesQueue) { remoteCandidatesQueue.add(candidate) }
                }
            }
        }
    }

    protected abstract fun updateLobbyStatus(message: String)
    protected abstract fun showCallUi()

    protected fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle("End Call")
            .setMessage("Are you sure you want to end this call?")
            .setPositiveButton("End Call") { _, _ -> teardownAndExit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    protected fun teardownAndExit() {
        viewModel.endCall()
        releaseResources()
        showSummaryDialog()
    }

    protected open fun releaseResources() {
        stopRecordingService()
        webRtcManager.cleanup()
        try {
            eglBase.release()
        } catch (e: Exception) {
            log("Error releasing WebRTC resources", e)
        }
    }

    private fun showSummaryDialog() {
        if (isFinishing) return
        val dialogBinding = DialogCallSummaryBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogBinding.root).setCancelable(false).create()

        dialogBinding.tvSummaryPhone.text = contactPhone
        dialogBinding.tvSummaryDuration.text = formatDuration(viewModel.elapsedSeconds.value)
        dialogBinding.tvSummaryBalance.text = formatDuration(viewModel.remainingBalance.value.coerceAtLeast(0))
        dialogBinding.btnSummaryOk.setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.show()
    }

    protected fun startRecordingService() {
        val intent = Intent(this, SecureHardwareRecordingService::class.java).apply {
            putExtra("ROOM_ID", roomId)
            putExtra("KIOSK_ID", "KIOSK_001")
            putExtra("SESSION_ID", UUID.randomUUID().toString())
            putExtra("INMATE_NAME", inmateName)
            putExtra("RECEIVER_NAME", contactPhone)
            putExtra("CALL_TYPE", if (isVideoMode) "VIDEO" else "AUDIO")
        }
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    protected fun stopRecordingService() {
        if (isServiceBound) {
            recordingService?.stopAndUpload()
            unbindService(serviceConnection)
            isServiceBound = false
        }
        stopService(Intent(this, SecureHardwareRecordingService::class.java))
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        viewModel.queueLocalCandidate(candidate)
    }

    override fun onIceConnected() {
        log("ICE Connection Established!")
        runOnUiThread {
            viewModel.updateRoomStatus("CONNECTED")
            webRtcManager.startStatsMonitoring(lifecycleScope)
            showCallUi()
        }
    }

    override fun onIceConnectionFailed() {
        log("ICE Connection Failed. Attempting ICE Restart...")
        runOnUiThread {
            resetNegotiation() // This will trigger a new offer with IceRestart if we add the flag
            // Actually, let's explicitly call createOffer(true)
            isOfferSent.set(false)
            createOffer(iceRestart = true)
        }
    }

    override fun onLocalTrackReady() {
        log("Local track is ready, creating offer if needed...")
        createOffer()
    }

    protected fun formatSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    protected fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes min $seconds sec"
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }
}
