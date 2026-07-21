package com.example.prisonconnect

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.prisonconnect.databinding.FragmentCallRoomBinding
import com.example.prisonconnect.model.CallRoom
import com.example.prisonconnect.repository.DbService
import com.example.prisonconnect.webrtc.SignalingClient
import com.example.prisonconnect.webrtc.SmsController
import com.example.prisonconnect.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.webrtc.*
import java.util.*

import com.example.prisonconnect.model.User
import com.example.prisonconnect.databinding.DialogCallSummaryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class CallRoomFragment : Fragment(), WebRtcManager.WebRtcListener, SignalingClient.SignalingListener {

    private var _binding: FragmentCallRoomBinding? = null
    private val binding get() = _binding!!

    private var roomId: String? = null
    private var contactPhone: String = ""
    private var kioskId: String = "KIOSK_001"
    private var sessionId: String = UUID.randomUUID().toString()
    private var roomOtp: String = ""
    private var roomToken: String = ""
    private var inmateId: String = ""
    private var inmateName: String = ""
    private var jailName: String = ""

    private lateinit var smsController: SmsController
    private lateinit var webRtcManager: WebRtcManager
    private lateinit var diagnosticHelper: com.example.prisonconnect.webrtc.CallDiagnosticHelper
    private var signalingClient: SignalingClient? = null

    private var recordingService: SecureHardwareRecordingService? = null
    private var isServiceBound = false
    private var roomPollJob: Job? = null
    private var smsJob: Job? = null
    private var timerJob: Job? = null
    private var isCallStarted = false
    private var isOtpSent = false
    private var isLinkSent = false
    private var currentRoomState = RoomState.UNKNOWN
    private var isRemoteDescriptionSet = false
    private val localCandidatesQueue = mutableListOf<IceCandidate>()
    private val remoteCandidatesQueue = mutableListOf<IceCandidate>()
    private var candidateBatchJob: Job? = null
    private var remoteVideoTrack: VideoTrack? = null
    
    // Call Timer & Balance
    private var elapsedSeconds = 0L
    private var remainingBalanceSeconds = 0L
    private var initialBalanceSeconds = 0L

    // UI States
    private var isMicEnabled = true
    private var isSpeakerEnabled = true
    private var callType: String = "VIDEO"
    private var overlaysVisible = true
    private var hideOverlaysJob: Job? = null

    // Queue for web-ready event if it arrives before peer connection is ready
    private var pendingWebReady = false
    private var connectTimeoutJob: Job? = null

    private val eglBase = EglBase.create()

    private enum class RoomState {
        WAITING, OTP_SENT, ACTIVE, CONNECTED, DISCONNECTED, TAMPER_KILLED, UNKNOWN
    }

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

    private val diagnosticLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            runPreCallDiagnostic()
        } else {
            showLobby(binding, "❌ Permissions denied. Cannot proceed with the call.")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCallRoomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val binding = _binding ?: return
        
        smsController = SmsController()
        webRtcManager = WebRtcManager(requireContext(), eglBase, this)
        diagnosticHelper = com.example.prisonconnect.webrtc.CallDiagnosticHelper(this)
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            webRtcManager.initialize()
            Log.d("CallRoom abhishek", "WebRtcManager initialized in background")
        }

        callType = arguments?.getString("call_type") ?: "VIDEO"
        inmateId = arguments?.getString("user_id") ?: "INMATE_001"
        inmateName = arguments?.getString("inmate_name") ?: arguments?.getString("full_name") ?: "Inmate"
        jailName = arguments?.getString("jail_name") ?: "jail"
        roomId = arguments?.getString("room_id") ?: "ROOM_${System.currentTimeMillis()}"
        contactPhone = arguments?.getString("phone_number") ?: ""

        Log.d("CallRoom abhishek", "onViewCreated - roomId: $roomId, contactPhone: $contactPhone, inmateName: $inmateName")

        initializeLobbyUi(binding)
        setupBackPressHandler()
        setupVideoDrag()
        setupOverlayLogic()
        setupAudioControls()

        viewLifecycleOwner.lifecycleScope.launch {
            val user = DbService.getDocument<User>("users", inmateId)
            initialBalanceSeconds = user?.balance_remaining_seconds ?: 0L
            remainingBalanceSeconds = initialBalanceSeconds

            initializeRoom(inmateId, callType)
            
            var room: CallRoom? = null
            for (i in 1..5) {
                delay(1000)
                if (_binding == null) return@launch
                room = DbService.getDocumentByColumn<CallRoom>("call_rooms", "room_id", roomId.toString())
                if (room != null) {
                    Log.d("CallRoom abhishek", "Room found after $i seconds")
                    break
                }
            }
            
            if (_binding == null) return@launch
            if (room == null) {
                showLobby(binding, "❌ Error: Room not created.")
                return@launch
            }
            
            signalingClient = SignalingClient(roomId!!, viewLifecycleOwner.lifecycleScope, this@CallRoomFragment)
            signalingClient?.connect()
            
            runPreCallDiagnostic()
            
            val receiverPhone = room.receiver_phone ?: room.receiverPhone
            if (!receiverPhone.isNullOrBlank()) {
                startSmsLoop(receiverPhone)
            }
            observeRoomStatus()
        }
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                updateRoomStatus("DISCONNECTED")
                teardownAndExit()
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVideoDrag() {
        val binding = _binding ?: return
        var dX = 0f
        var dY = 0f

        binding.localView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    resetOverlayTimer()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayLogic() {
        val binding = _binding ?: return
        binding.videoClickOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                toggleOverlays()
            }
            true
        }

        binding.btnVideoHangup.setOnClickListener {
            updateRoomStatus("DISCONNECTED")
            teardownAndExit()
        }

        binding.btnVideoMic.setOnClickListener {
            isMicEnabled = !isMicEnabled
            webRtcManager.setAudioEnabled(isMicEnabled)
            binding.btnVideoMic.setIconResource(if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
            binding.btnAudioMic.setIconResource(if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
        }

        binding.btnVideoSwitch.setOnClickListener {
            webRtcManager.switchCamera()
        }
    }

    private fun setupAudioControls() {
        val binding = _binding ?: return
        binding.btnAudioHangup.setOnClickListener {
            updateRoomStatus("DISCONNECTED")
            teardownAndExit()
        }

        binding.btnAudioMic.setOnClickListener {
            isMicEnabled = !isMicEnabled
            webRtcManager.setAudioEnabled(isMicEnabled)
            binding.btnAudioMic.setIconResource(if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
            binding.btnVideoMic.setIconResource(if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
        }

        binding.btnAudioSpeaker.setOnClickListener {
            isSpeakerEnabled = !isSpeakerEnabled
            webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
            binding.btnAudioSpeaker.alpha = if (isSpeakerEnabled) 1.0f else 0.5f
        }

        binding.btnAudioInfo.setOnClickListener {
            showCallInfoTooltip()
        }
    }

    private fun showCallInfoTooltip() {
        val info = "Inmate: $inmateName\nNumber: $contactPhone\nDuration: ${formatSeconds(elapsedSeconds)}\nBalance: ${formatSeconds(remainingBalanceSeconds)}\nFacility: $jailName"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Call Details")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toggleOverlays() {
        if (overlaysVisible) hideOverlays() else showOverlays()
    }

    private fun showOverlays() {
        val binding = _binding ?: return
        overlaysVisible = true
        binding.videoHeader.animate().alpha(1f).setDuration(300).start()
        binding.videoFooter.animate().alpha(1f).setDuration(300).start()
        resetOverlayTimer()
    }

    private fun hideOverlays() {
        val binding = _binding ?: return
        overlaysVisible = false
        binding.videoHeader.animate().alpha(0f).setDuration(300).start()
        binding.videoFooter.animate().alpha(0f).setDuration(300).start()
    }

    private fun resetOverlayTimer() {
        hideOverlaysJob?.cancel()
        hideOverlaysJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000)
            hideOverlays()
        }
    }

    private fun runPreCallDiagnostic() {
        val binding = _binding ?: return
        val result = diagnosticHelper.performFullDiagnostic(isVideo = true)
        val plan = diagnosticHelper.getResolutionPlan(result, diagnosticLauncher)

        if (result == com.example.prisonconnect.webrtc.CallDiagnosticHelper.DiagnosticResult.Success) {
            if (currentRoomState == RoomState.ACTIVE && !isCallStarted) {
                activateCallSession()
            }
        } else {
            showLobby(binding, "⚠️ ${plan.message}")
            if (plan.actionLabel != null && plan.action != null) {
                binding.btnCancelCall.text = plan.actionLabel
                binding.btnCancelCall.setOnClickListener { plan.action.invoke() }
            }
        }
    }

    private fun initializeLobbyUi(binding: FragmentCallRoomBinding) {
        binding.lobbyContainer.visibility = View.VISIBLE
        binding.videoCallContainer.visibility = View.GONE
        binding.audioCallContainer.visibility = View.GONE
        binding.tvLobbyStatus.text = "Waiting for terminal response..."
        binding.btnCancelCall.setOnClickListener {
            updateRoomStatus("DISCONNECTED")
            teardownAndExit()
        }
    }

    private suspend fun initializeRoom(inmateId: String, callType: String) {
        val id = roomId ?: return
        try {
            roomOtp = (100000..999999).random().toString()
            roomToken = "sess_${UUID.randomUUID().toString().replace("-", "")}"
            val roomData: Map<String, Any> = mapOf(
                "room_id" to id,
                "kiosk_id" to kioskId,
                "inmate_id" to inmateId,
                "call_type" to callType,
                "room_status" to "WAITING",
                "receiver_phone" to contactPhone,
                "otp" to roomOtp,
                "token" to roomToken,
                "webrtc_signaling" to mapOf("offer" to null, "answer" to null, "iceCandidates" to emptyList<Map<String, Any>>())
            )
            DbService.insertRaw("call_rooms", roomData)
        } catch (ex: Exception) { Log.e("CallRoom", "initRoom failed", ex) }
    }

    private fun observeRoomStatus() {
        roomId?.let { id ->
            roomPollJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    if (_binding == null) break
                    try {
                        val room: CallRoom? = DbService.getDocumentByColumn("call_rooms", "room_id", id)
                        val stateValue = room?.room_status?.uppercase() ?: "UNKNOWN"
                        val receiverPhone = room?.receiver_phone ?: room?.receiverPhone
                        val binding = _binding ?: break

                        when (stateValue) {
                            "WAITING" -> {
                                currentRoomState = RoomState.WAITING
                                showLobby(binding, "📱 Call link sent!")
                                if (!receiverPhone.isNullOrBlank() && smsJob?.isActive != true) startSmsLoop(receiverPhone)
                            }
                            "OTP_SENT" -> {
                                currentRoomState = RoomState.OTP_SENT
                                showLobby(binding, "✅ Link opened! Sending code...")
                                if (!receiverPhone.isNullOrBlank()) sendOtpSms(receiverPhone)
                            }
                            "ACTIVE" -> {
                                currentRoomState = RoomState.ACTIVE
                                showLobby(binding, "🔐 Access verified! Connecting...")
                                runPreCallDiagnostic()
                            }
                            "CONNECTED" -> {
                                if (currentRoomState != RoomState.CONNECTED) {
                                    currentRoomState = RoomState.CONNECTED
                                    showCallUi(binding)
                                    startCallTimer()
                                }
                            }
                            "DISCONNECTED", "TAMPER_KILLED" -> teardownAndExit()
                        }
                    } catch (e: Exception) { Log.e("CallRoom", "Poll failed", e) }
                    delay(3000)
                }
            }
        }
    }

    private fun startCallTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && currentRoomState == RoomState.CONNECTED) {
                delay(1000)
                val binding = _binding ?: break
                elapsedSeconds++
                remainingBalanceSeconds--
                val timeStr = formatSeconds(elapsedSeconds)
                binding.tvVideoDuration.text = timeStr
                binding.tvVideoBalance.text = "Bal: ${formatSeconds(remainingBalanceSeconds)}"
                binding.tvAudioDuration.text = timeStr
                if (remainingBalanceSeconds <= 0) {
                    updateRoomStatus("DISCONNECTED")
                    teardownAndExit()
                    break
                }
            }
        }
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

    private fun startSmsLoop(phone: String) {
        smsJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!isLinkSent && phone.isNotBlank()) {
                isLinkSent = true
                val smsLink = "https://prisonconnect-call.rf.gd/index.html?room=$roomId&token=$roomToken"
                val linkMessage = "PrisonConnect: You have a video call request from Inmate: $inmateName at $jailName. Join: $smsLink"
                val result = smsController.sendSmsViaSupabase(phone, linkMessage)
                if (!result.isSuccess) isLinkSent = false
            }
        }
    }

    private fun sendOtpSms(phone: String) {
        if (roomOtp.isBlank() || isOtpSent) return
        isOtpSent = true
        viewLifecycleOwner.lifecycleScope.launch {
            val otpMessage = "PrisonConnect: Your access code is: $roomOtp"
            val result = smsController.sendSmsViaSupabase(phone, otpMessage)
            if (!result.isSuccess) isOtpSent = false
        }
    }

    private fun activateCallSession() {
        if (isCallStarted) return
        isCallStarted = true
        val binding = _binding ?: return
        try {
            binding.localView.init(eglBase.eglBaseContext, null)
            binding.localView.setMirror(true)
            binding.remoteView.init(eglBase.eglBaseContext, null)
            webRtcManager.setupPeerConnection()
            webRtcManager.startLocalStream(binding.localView)
            if (pendingWebReady) {
                onWebReady()
                pendingWebReady = false
            }
            startRecordingService()
            showCallUi(binding)
            startConnectTimeout()
        } catch (e: Exception) { isCallStarted = false }
    }

    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(25000)
            val binding = _binding ?: return@launch
            if (currentRoomState != RoomState.CONNECTED && isCallStarted) {
                showLobby(binding, "❌ Connection failed.")
            }
        }
    }

    override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
        remoteVideoTrack = videoTrack
        val binding = _binding ?: return
        videoTrack.addSink(binding.remoteView)
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        synchronized(localCandidatesQueue) { localCandidatesQueue.add(candidate) }
        if (candidateBatchJob?.isActive != true) {
            candidateBatchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300)
                val candidatesToSend = synchronized(localCandidatesQueue) {
                    val list = localCandidatesQueue.toList()
                    localCandidatesQueue.clear()
                    list
                }
                if (candidatesToSend.isNotEmpty()) {
                    val jsonArray = JsonArray(candidatesToSend.map {
                        buildJsonObject {
                            put("sdpMid", it.sdpMid ?: "")
                            put("sdpMLineIndex", it.sdpMLineIndex)
                            put("candidate", it.sdp)
                        }
                    })
                    signalingClient?.sendCandidateBatch(jsonArray)
                }
            }
        }
    }

    override fun onIceConnected() { 
        connectTimeoutJob?.cancel()
        updateRoomStatus("CONNECTED") 
    }
    
    override fun onIceConnectionFailed() {
        connectTimeoutJob?.cancel()
        val binding = _binding ?: return
        if (isCallStarted && currentRoomState != RoomState.CONNECTED) {
            showLobby(binding, "❌ Connection failed.")
        }
    }

    override fun onSiteOpened() { 
        val binding = _binding ?: return
        if (currentRoomState == RoomState.WAITING) showLobby(binding, "✅ Link opened!") 
    }
    override fun onOtpVerified() { 
        val binding = _binding ?: return
        if (currentRoomState == RoomState.OTP_SENT) showLobby(binding, "🔐 Access verified!") 
    }
    
    override fun onAnswerReceived(sdp: String, type: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        webRtcManager.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                synchronized(remoteCandidatesQueue) {
                    remoteCandidatesQueue.forEach { webRtcManager.peerConnection?.addIceCandidate(it) }
                    remoteCandidatesQueue.clear()
                }
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sessionDescription)
    }

    override fun onCandidateBatchReceived(candidates: List<JsonObject>) {
        candidates.forEach { candidateData ->
            val sdpMid = candidateData["sdpMid"]?.jsonPrimitive?.content
            val sdpMLineIndex = candidateData["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val candidateSdp = candidateData["candidate"]?.jsonPrimitive?.content ?: candidateData["sdp"]?.jsonPrimitive?.content
            if (candidateSdp != null) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                if (isRemoteDescriptionSet) {
                    webRtcManager.peerConnection?.addIceCandidate(candidate)
                } else {
                    synchronized(remoteCandidatesQueue) { remoteCandidatesQueue.add(candidate) }
                }
            }
        }
    }

    override fun onWebReady() {
        val pc = webRtcManager.peerConnection
        if (pc == null) { pendingWebReady = true; return }
        if (pc.remoteDescription == null && pc.signalingState() == PeerConnection.SignalingState.STABLE) {
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { signalingClient?.sendOffer(sdp.description, sdp.type.canonicalForm()) }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        }
    }

    override fun onHangupReceived() { teardownAndExit() }

    private fun showLobby(binding: FragmentCallRoomBinding, message: String) {
        binding.lobbyContainer.visibility = View.VISIBLE
        binding.videoCallContainer.visibility = View.GONE
        binding.audioCallContainer.visibility = View.GONE
        binding.tvLobbyStatus.text = message
    }

    private fun showCallUi(binding: FragmentCallRoomBinding) {
        binding.lobbyContainer.visibility = View.GONE
        if (callType == "VIDEO") {
            binding.videoCallContainer.visibility = View.VISIBLE
            binding.audioCallContainer.visibility = View.GONE
            binding.tvVideoName.text = inmateName
            webRtcManager.setSpeakerphoneOn(true)
        } else {
            binding.videoCallContainer.visibility = View.GONE
            binding.audioCallContainer.visibility = View.VISIBLE
            binding.tvAudioName.text = inmateName
            binding.tvAudioPhone.text = contactPhone
            webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
        }
    }

    private fun updateRoomStatus(status: String) {
        roomId?.let { id ->
            lifecycleScope.launch {
                try { DbService.updateFieldsByColumn("call_rooms", "room_id", id, mapOf("room_status" to status)) }
                catch (ex: Exception) { Log.e("CallRoom", "status update failed", ex) }
            }
        }
    }

    private fun startRecordingService() {
        val intent = Intent(requireContext(), SecureHardwareRecordingService::class.java).apply {
            putExtra("ROOM_ID", roomId); putExtra("KIOSK_ID", kioskId); putExtra("SESSION_ID", sessionId)
        }
        requireContext().startForegroundService(intent)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRecordingService() {
        if (isServiceBound) {
            try { recordingService?.stopAndUpload(); requireContext().unbindService(serviceConnection) } catch (e: Exception) {}
            isServiceBound = false
        }
        try { requireContext().stopService(Intent(requireContext(), SecureHardwareRecordingService::class.java)) } catch (e: Exception) {}
    }

    private fun stopSmsLoop() { smsJob?.cancel(); smsJob = null }

    private fun teardownAndExit() {
        releaseResources()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val newBalance = remainingBalanceSeconds.coerceAtLeast(0)
                DbService.updateFields("users", inmateId, mapOf("balance_remaining_seconds" to newBalance))
            } catch (e: Exception) {}
            delay(1000)
            deleteRoom()
            showSummaryDialog()
        }
    }

    private fun releaseResources() {
        stopSmsLoop(); timerJob?.cancel(); connectTimeoutJob?.cancel(); candidateBatchJob?.cancel(); roomPollJob?.cancel()
        stopRecordingService(); smsController.unregisterReceivers(); webRtcManager.cleanup()
        signalingClient?.sendHangupAndDisconnect()
        try { eglBase.release() } catch (e: Exception) {}
    }

    private fun showSummaryDialog() {
        if (_binding == null) { exitToDashboard(); return }
        val dialog = MaterialAlertDialogBuilder(requireContext()).create()
        val dialogBinding = DialogCallSummaryBinding.inflate(layoutInflater)
        dialog.setView(dialogBinding.root); dialog.setCancelable(false)
        dialogBinding.tvSummaryPhone.text = contactPhone
        dialogBinding.tvSummaryDuration.text = formatDuration(elapsedSeconds)
        dialogBinding.tvSummaryBalance.text = formatDuration(remainingBalanceSeconds.coerceAtLeast(0))
        dialogBinding.btnSummaryOk.setOnClickListener { dialog.dismiss(); exitToDashboard() }
        dialog.show()
    }

    private fun exitToDashboard() {
        (requireActivity() as? KioskMainActivity)?.navigateToFragment(DashboardFragment().apply {
            arguments = Bundle().apply { putString("user_id", inmateId) }
        }, false)
    }

    private fun deleteRoom() {
        roomId?.let { id -> lifecycleScope.launch { try { DbService.deleteByColumn("call_rooms", "room_id", id) } catch (ex: Exception) {} } }
    }

    override fun onDestroyView() {
        releaseResources()
        super.onDestroyView()
        _binding = null
    }
}