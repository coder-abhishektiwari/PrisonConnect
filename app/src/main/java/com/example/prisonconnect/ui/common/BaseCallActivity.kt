package com.example.prisonconnect.ui.common

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    protected var contactName: String= ""
    protected var roomOtp: String = ""
    protected var roomToken: String = ""
    protected var isOtpSent: Boolean = false

    // WebRTC & Signaling
    protected lateinit var webRtcManager: WebRtcManager
    protected lateinit var smsController: SmsController
    protected val eglBase: EglBase = EglBase.create()

    protected var isCallStarted = AtomicBoolean(false)
    protected var isOfferSent = AtomicBoolean(false)
    protected var isEnding = AtomicBoolean(false)
    protected var isRemoteDescriptionSet = false
    protected var isBrowserMediaReady = AtomicBoolean(false)
    protected var isNegotiating = AtomicBoolean(false)
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
            updateLobbyStatus("Permissions denied. Cannot proceed.", LobbyStatusType.FAILURE)
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
                val status = viewModel.roomStatus.value
                if (status == "WAITING" || status == "OTP_SENT") {
                    cancelLobbyCall()
                } else {
                    confirmExit()
                }
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
        contactName = intent.getStringExtra("contact_name") ?: ""
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
                updateLobbyStatus("Hardware diagnostic failed.", LobbyStatusType.FAILURE)
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
                updateLobbyStatus("Failed to initialize call room.", LobbyStatusType.FAILURE)
                return@launch
            }

            val typeStr = if (isVideoMode) "Video" else "Audio"
            val smsLink =
                "https://prisonconnect-call.rf.gd/index.html?room=$roomId&token=$roomToken"

            val linkMessage =
                "PrisonConnect: $typeStr call from $inmateName at $jailName. Join: $smsLink"

            // Log mode shows credentials immediately. Other modes hide them unless failure occurs.
            val isLogMode = smsController.getProviderName() == "LogSmsProvider"
            if (isLogMode) {
                withContext(Dispatchers.Main) {
                    updateLobbyWithCredentials(smsLink, roomOtp)
                    updateLobbyStatus("Copy and share the credentials manually", LobbyStatusType.PENDING)
                }
                // Log it internally but don't show "Sending..." status
                smsController.sendSmsViaSupabase(contactPhone, linkMessage)
                return@launch
            }

            updateLobbyStatus("Sending SMS link...", LobbyStatusType.PENDING)
            val result = smsController.sendSmsViaSupabase(contactPhone, linkMessage)

            result.onSuccess {
                updateLobbyStatus("Call link sent to $contactPhone", LobbyStatusType.SUCCESS)
            }.onFailure { error ->
                log("SMS sending failed: ${error.message}")
                updateLobbyStatus("SMS failed: ${error.message}", LobbyStatusType.FAILURE)
                // Reveal credentials on failure
                withContext(Dispatchers.Main) {
                    updateLobbyWithCredentials(smsLink, roomOtp)
                }
            }
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
        val isLogMode = smsController.getProviderName() == "LogSmsProvider"
        log("Room status: $status")
        when (status) {
            "WAITING" -> {
                if (isLogMode) updateLobbyStatus("Copy and share the credentials manually", LobbyStatusType.PENDING)
                else updateLobbyStatus("Waiting for terminal...", LobbyStatusType.PENDING)
            }
            "OTP_SENT" -> {
                if (isLogMode) {
                    updateLobbyStatus("Family opened link! Share the OTP code above.", LobbyStatusType.SUCCESS)
                } else {
                    updateLobbyStatus("Link opened! Sending code...", LobbyStatusType.PENDING)
                    if (!isOtpSent) lifecycleScope.launch { sendOtpSms() }
                }
            }
            "ACTIVE" -> {
                updateLobbyStatus("Access verified! Connecting...", LobbyStatusType.SUCCESS)
                onCallActivated()
            }
            "CONNECTED" -> showCallUi()
            "DISCONNECTED", "TAMPER_KILLED" -> teardownAndExit()
        }
    }

    private fun handleSignalingEvent(event: CallViewModel.SignalingEvent) {
        when (event) {
            is CallViewModel.SignalingEvent.SiteOpened -> {
                updateLobbyStatus("Link opened!", LobbyStatusType.SUCCESS)
                lifecycleScope.launch {
                    try {
                        DbService.updateFieldsByColumn("call_rooms", "room_id", roomId, mapOf("room_status" to "OTP_SENT"))
                        sendOtpSms()
                    } catch (e: Exception) { log("Failed site-opened update", e) }
                }
            }
            is CallViewModel.SignalingEvent.OtpVerified -> {
                updateLobbyStatus("Access verified!", LobbyStatusType.SUCCESS)
                onCallActivated()
            }
            is CallViewModel.SignalingEvent.WebReady -> {
                log("Web client has connected to signaling channel.")
            }
            is CallViewModel.SignalingEvent.BrowserMediaReady -> {
                log("Remote browser confirms media tracks are attached.")
                isBrowserMediaReady.set(true)
                synchronizedNegotiation()
            }
            is CallViewModel.SignalingEvent.AnswerReceived -> setRemoteDescription(event.sdp, event.type)
            is CallViewModel.SignalingEvent.CandidatesReceived -> handleRemoteCandidates(event.candidates)
            is CallViewModel.SignalingEvent.HangupReceived -> teardownAndExit()
        }
    }

    private fun resetNegotiation() {
        if (!isNegotiating.compareAndSet(false, true)) {
            log("resetNegotiation: Already negotiating, skipping")
            return
        }

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
                // High-Quality SDP Munging
                val mungedSdp = if (isVideoMode) {
                    boostVideoQuality(sdp.description)
                } else sdp.description
                
                val finalSdp = SessionDescription(sdp.type, mungedSdp)

                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        viewModel.sendOffer(finalSdp.description, finalSdp.type.canonicalForm())
                        log("Offer sent successfully")
                        isNegotiating.set(false)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {
                        log("Failed to set local description: $error")
                        isNegotiating.set(false)
                        isOfferSent.set(false)
                    }
                    override fun onSetFailure(error: String?) {
                        log("Failed to set local description: $error")
                        isNegotiating.set(false)
                        isOfferSent.set(false)
                    }
                }, finalSdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                log("Failed to create offer: $error")
                isNegotiating.set(false)
                isOfferSent.set(false)
            }
            override fun onSetFailure(error: String?) {
                isNegotiating.set(false)
                isOfferSent.set(false)
            }
        }, constraints)
    }

    /**
     * Injects bandwidth parameters into the SDP to force higher video quality.
     */
    private fun boostVideoQuality(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        var videoLineIndex = -1
        
        for (i in lines.indices) {
            if (lines[i].startsWith("m=video")) {
                videoLineIndex = i
                break
            }
        }

        if (videoLineIndex != -1) {
            // Force 4Mbps for crystal clear video
            lines.add(videoLineIndex + 1, "b=AS:4000")
            lines.add(videoLineIndex + 2, "b=TIAS:4000000")
            
            for (i in lines.indices) {
                if (lines[i].startsWith("a=fmtp") && (lines[i].contains("VP8") || lines[i].contains("H264"))) {
                    lines[i] = lines[i] + ";x-google-min-bitrate=1500;x-google-max-bitrate=4000"
                }
            }
        }
        return lines.joinToString("\r\n")
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

    private fun updateLobbyWithCredentials(link: String, otp: String) {
        val llLink = findViewById<LinearLayout>(com.example.prisonconnect.R.id.ll_lobby_link)
        val tvLink = findViewById<TextView>(com.example.prisonconnect.R.id.tv_lobby_link)
        val btnCopyLink = findViewById<View>(com.example.prisonconnect.R.id.btn_copy_link)

        val llOtp = findViewById<LinearLayout>(com.example.prisonconnect.R.id.ll_lobby_otp)
        val tvOtp = findViewById<TextView>(com.example.prisonconnect.R.id.tv_lobby_otp)
        val btnCopyOtp = findViewById<View>(com.example.prisonconnect.R.id.btn_copy_otp)

        val btnCopyBoth = findViewById<View>(com.example.prisonconnect.R.id.btn_copy_both)

        if (llLink != null && tvLink != null) {
            tvLink.text = link
            llLink.visibility = View.VISIBLE
            btnCopyLink?.setOnClickListener {
                copyToClipboard("Call Link", link)
            }
        }

        if (llOtp != null && tvOtp != null) {
            tvOtp.text = "OTP: $otp"
            llOtp.visibility = View.VISIBLE
            btnCopyOtp?.setOnClickListener {
                copyToClipboard("OTP", otp)
            }
        }

        if (btnCopyBoth != null) {
            btnCopyBoth.visibility = View.VISIBLE
            btnCopyBoth.setOnClickListener {
                val combinedText = "Link: $link | OTP: $otp"
                copyToClipboard("Link + OTP", combinedText)
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    enum class LobbyStatusType {
        SUCCESS, FAILURE, PENDING
    }

    protected fun cancelLobbyCall() {
        log("Canceling call from lobby")
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                viewModel.deleteRoom()
                viewModel.endCall()
            } catch (e: Exception) {
                log("Error canceling lobby call", e)
            }
            
            // Cleanup without showing summary dialog
            try {
                stopRecordingService()
                webRtcManager.cleanup()
                eglBase.release()
            } catch (e: Exception) {
                log("Error releasing resources during cancel", e)
            }
            
            finish()
        }
    }

    protected abstract fun updateLobbyStatus(message: String, type: LobbyStatusType = LobbyStatusType.PENDING)
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
        if (!isEnding.compareAndSet(false, true)) {
            log("teardownAndExit: Already in progress, skipping")
            return
        }
        
        log("Initiating call teardown...")
        
        lifecycleScope.launch(Dispatchers.Main) {
            // 1. Signal end to server/peer
            try {
                viewModel.endCall()
            } catch (e: Exception) {
                log("Error signaling end call", e)
            }
            
            // 2. Clear UI/Hardware immediately to release camera/mic
            releaseResources()
            
            // 3. Show summary dialog
            // We use a small post to ensure the UI has settled after hardware release
            delay(200)
            showSummaryDialog()
        }
    }

    protected open fun releaseResources() {
        try {
            stopRecordingService()
            webRtcManager.cleanup()
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
        // Just bind initially to get audio hooks. 
        // Do NOT call startForegroundService here to avoid premature notification.
        val intent = Intent(this, SecureHardwareRecordingService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    protected fun promoteRecordingServiceToForeground() {
        val intent = Intent(this, SecureHardwareRecordingService::class.java).apply {
            putExtra("ROOM_ID", roomId)
            putExtra("KIOSK_ID", "KIOSK_001")
            putExtra("SESSION_ID", UUID.randomUUID().toString())
            putExtra("INMATE_NAME", inmateName)
            putExtra("RECEIVER_NAME", contactPhone)
            putExtra("CALL_TYPE", if (isVideoMode) "VIDEO" else "AUDIO")
        }
        startForegroundService(intent)
    }

    protected fun stopRecordingService() {
        if (isServiceBound) {
            recordingService?.stopAndUpload()
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        viewModel.queueLocalCandidate(candidate)
    }

    override fun onIceConnected() {
        log("ICE Connection Established!")
        runOnUiThread {
            viewModel.updateRoomStatus("CONNECTED")
            webRtcManager.startStatsMonitoring(lifecycleScope)
            
            // Set intelligent defaults for audio output
            webRtcManager.setSpeakerphoneOn(isVideoMode)
            
            showCallUi()
        }
    }

    override fun onIceConnectionFailed() {
        log("ICE Connection Failed. Attempting ICE Restart...")
        runOnUiThread {
            // FIX: Single path for ICE restart. Reset state and call createOfferInternal(pc, iceRestart = true)
            val pc = webRtcManager.currentPeerConnection ?: return@runOnUiThread
            isOfferSent.set(true) // Guard against standard createOffer while restarting
            isRemoteDescriptionSet = false
            synchronized(remoteCandidatesQueue) { remoteCandidatesQueue.clear() }
            createOfferInternal(pc, true)
        }
    }

    override fun onLocalTrackReady() {
        log("Local media tracks (Camera/Mic) are attached.")
        synchronizedNegotiation()
    }

    /**
     * Ensures negotiation only starts when BOTH the Kiosk and the Browser are ready.
     */
    private fun synchronizedNegotiation() {
        if (!isCallStarted.get()) return
        
        val kioskReady = webRtcManager.isTracksReady
        val browserReady = isBrowserMediaReady.get()
        
        log("Checking synchronization: Kiosk=$kioskReady, Browser=$browserReady")
        
        if (kioskReady && browserReady) {
            log("Handshake COMPLETE. Starting negotiation.")
            resetNegotiation()
        }
    }

    protected fun formatSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    protected fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return buildString {
            if (hours > 0) append("$hours hr ")
            if (minutes > 0 || hours > 0) append("$minutes min ")
            append("$seconds sec")
        }
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }
}
