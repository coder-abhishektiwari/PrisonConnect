package com.example.prisonconnect

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
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
            // Re-run diagnostic to proceed
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
        
        // Offload heavy WebRTC initialization to background
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            webRtcManager.initialize()
            Log.d("CallRoom abhishek", "WebRtcManager initialized in background")
        }

        val callType = arguments?.getString("call_type") ?: "VIDEO"
        inmateId = arguments?.getString("user_id") ?: "INMATE_001"
        inmateName = arguments?.getString("inmate_name") ?: arguments?.getString("full_name") ?: "Inmate"
        roomId = arguments?.getString("room_id") ?: "ROOM_${System.currentTimeMillis()}"
        contactPhone = arguments?.getString("phone_number") ?: ""

        Log.d("CallRoom abhishek", "onViewCreated - roomId: $roomId, contactPhone: $contactPhone, inmateName: $inmateName")

        initializeLobbyUi(binding)
        binding.timerOverlay.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            // Fetch inmate balance
            val user = DbService.getDocument<User>("users", inmateId)
            initialBalanceSeconds = user?.balance_remaining_seconds ?: 0L
            remainingBalanceSeconds = initialBalanceSeconds

            initializeRoom(inmateId, callType)
            
            // Wait for room to be created and verify
            var room: CallRoom? = null
            for (i in 1..5) {
                delay(1000)
                if (_binding == null) return@launch
                room = DbService.getDocumentByColumn<CallRoom>("call_rooms", "room_id", roomId.toString())
                if (room != null) {
                    Log.d("CallRoom abhishek", "Room found after $i seconds - receiver_phone: ${room.receiver_phone}, receiverPhone: ${room.receiverPhone}")
                    break
                }
            }
            
            if (_binding == null) return@launch
            if (room == null) {
                showLobby(binding, "❌ Error: Room not created. Please try again.")
                return@launch
            }
            
            // SignalingClient connected early for room
            signalingClient = SignalingClient(roomId!!, viewLifecycleOwner.lifecycleScope, this@CallRoomFragment)
            signalingClient?.connect()
            Log.d("CallRoom abhishek", "SignalingClient connected for room: $roomId")
            
            runPreCallDiagnostic()
            
            val receiverPhone = room.receiver_phone ?: room.receiverPhone
            if (!receiverPhone.isNullOrBlank()) {
                startSmsLoop(receiverPhone)
            }
            observeRoomStatus()
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

    private fun checkAndStartSms() {
        if (currentRoomState == RoomState.WAITING && smsJob?.isActive != true) {
            viewLifecycleOwner.lifecycleScope.launch {
                val room = roomId?.let { 
                    DbService.getDocumentByColumn<CallRoom>("call_rooms", "room_id", it)
                }
                val receiverPhone = room?.receiver_phone ?: room?.receiverPhone
                if (!receiverPhone.isNullOrBlank()) {
                    startSmsLoop(receiverPhone)
                }
            }
        }
    }

    private fun initializeLobbyUi(binding: FragmentCallRoomBinding) {
        binding.lobbyContainer.visibility = View.VISIBLE
        binding.videoContainer.visibility = View.GONE
        binding.lobbyProgress.visibility = View.VISIBLE
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

            val existing: CallRoom? = DbService.getDocumentByColumn("call_rooms", "room_id", id)
            if (existing != null) {
                DbService.updateFieldsByColumn("call_rooms", "room_id", id, mapOf(
                    "kiosk_id" to kioskId,
                    "inmate_id" to inmateId,
                    "call_type" to callType,
                    "room_status" to "WAITING",
                    "otp" to roomOtp,
                    "token" to roomToken,
                    "receiver_phone" to contactPhone
                ))
            } else {
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
            }
        } catch (ex: Exception) {
            Log.e("CallRoom abhishek", "initializeRoom failed", ex)
        }
    }


    private fun observeRoomStatus() {
        roomId?.let { id ->
            roomPollJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(500L)
                while (isActive) {
                    if (_binding == null) break
                    try {
                        val room: CallRoom? = DbService.getDocumentByColumn("call_rooms", "room_id", id)
                        val stateValue = room?.room_status?.uppercase() ?: "UNKNOWN"
                        val receiverPhone = room?.receiver_phone ?: room?.receiverPhone

                        val binding = _binding
                        if (binding == null) {
                            Log.w("CallRoom abhishek", "Binding is null, skipping UI update")
                            delay(3000L)
                            continue
                        }

                        when (stateValue) {
                            "WAITING" -> {
                                currentRoomState = RoomState.WAITING
                                showLobby(binding, "📱 Call link sent! Waiting for receiver to open...")
                                stopRecordingService()
                                if (!receiverPhone.isNullOrBlank() && smsJob?.isActive != true) {
                                    startSmsLoop(receiverPhone)
                                }
                            }
                            "OTP_SENT" -> {
                                currentRoomState = RoomState.OTP_SENT
                                showLobby(binding, "✅ Link opened! Sending access code...")
                                if (!receiverPhone.isNullOrBlank()) {
                                    sendOtpSms(receiverPhone)
                                }
                                stopSmsLoop()
                            }
                            "ACTIVE" -> {
                                currentRoomState = RoomState.ACTIVE
                                showLobby(binding, "🔐 Access code verified! Connecting to call...")
                                stopSmsLoop()
                                runPreCallDiagnostic()
                            }
                            "CONNECTED" -> {
                                currentRoomState = RoomState.CONNECTED
                                showLobby(binding, "✅ Call connected!")
                                startCallTimer()
                            }
                            "DISCONNECTED", "TAMPER_KILLED" -> teardownAndExit()
                            else -> showLobby(binding, "Waiting for terminal response...")
                        }
                    } catch (e: Exception) {
                        Log.e("CallRoom abhishek", "Room polling failed", e)
                    }
                    delay(3000L)
                }
            }
        }
    }

    private fun startCallTimer() {
        if (timerJob?.isActive == true) return
        
        val binding = _binding ?: return
        binding.timerOverlay.visibility = View.VISIBLE
        
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && currentRoomState == RoomState.CONNECTED) {
                delay(1000)
                val currentBinding = _binding ?: break
                
                elapsedSeconds++
                remainingBalanceSeconds--
                
                currentBinding.tvCallDuration.text = formatSeconds(elapsedSeconds)
                currentBinding.tvRemainingBalance.text = "${formatSeconds(remainingBalanceSeconds)} remaining"
                
                if (remainingBalanceSeconds <= 0) {
                    Log.d("CallRoom abhishek", "Balance exhausted, hanging up")
                    updateRoomStatus("DISCONNECTED")
                    teardownAndExit()
                    break
                }
            }
        }
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes min $seconds sec"
    }

    private var isLinkSent = false
    
    private fun startSmsLoop(phone: String) {
        Log.d("CallRoom abhishek", "startSmsLoop called with phone: $phone, isLinkSent: $isLinkSent, contactPhone: $contactPhone")
        smsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Use contactPhone as fallback if phone is empty
                val targetPhone = if (phone.isNotBlank()) phone else contactPhone
                if (!isLinkSent && targetPhone.isNotBlank()) {
                    isLinkSent = true
                    val smsLink = "https://prisonconnect-call.rf.gd/index.html?room=$roomId&token=$roomToken"
                    val linkMessage = "PrisonConnect: You have a video call request from Inmate: $inmateName at $kioskId. Join: $smsLink"
                    Log.d("CallRoom abhishek", "Sending SMS link via Supabase to: $targetPhone")
                    
                    val result = smsController.sendSmsViaSupabase(targetPhone, linkMessage)
                    val binding = _binding ?: return@launch
                    
                    if (result.isSuccess) {
                        showLobby(binding, "📱 Call link sent! Waiting for receiver to open...")
                    } else {
                        isLinkSent = false // Allow retry
                        Log.e("CallRoom abhishek", "SMS link failed", result.exceptionOrNull())
                        showLobby(binding, "❌ SMS link failed: ${result.exceptionOrNull()?.message}")
                    }
                } else if (targetPhone.isBlank()) {
                    Log.w("CallRoom abhishek", "SMS not sent - phone blank")
                    val binding = _binding ?: return@launch
                    showLobby(binding, "❌ SMS not sent. Invalid phone number.")
                }
            } catch (ex: Exception) {
                Log.e("CallRoom abhishek", "SMS send loop failed", ex)
                val binding = _binding ?: return@launch
                showLobby(binding, "❌ SMS error: ${ex.message}")
            }
            while (isActive && currentRoomState == RoomState.WAITING) delay(1000)
        }
    }

    private fun sendOtpSms(phone: String) {
        if (roomOtp.isBlank() || isOtpSent) return
        isOtpSent = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val otpMessage = "PrisonConnect: Your access code is: $roomOtp. Enter this code on the website to join the call."
                Log.d("CallRoom abhishek", "Sending OTP via Supabase to: $phone")
                
                val result = smsController.sendSmsViaSupabase(phone, otpMessage)
                val binding = _binding ?: return@launch
                
                if (result.isSuccess) {
                    showLobby(binding, "✅ Access code sent! Please check your messages.")
                } else {
                    isOtpSent = false // Reset on failure
                    Log.e("CallRoom abhishek", "OTP SMS failed", result.exceptionOrNull())
                    showLobby(binding, "❌ Failed to send access code: ${result.exceptionOrNull()?.message}")
                }
            } catch (ex: Exception) {
                isOtpSent = false // Reset on failure
                val binding = _binding ?: return@launch
                showLobby(binding, "❌ Failed to send access code. Please try again.")
            }
        }
    }

    private fun activateCallSession() {
        if (isCallStarted) return
        isCallStarted = true
        stopSmsLoop()

        val binding = _binding ?: run {
            Log.w("CallRoom abhishek", "activateCallSession: Binding null")
            isCallStarted = false
            return
        }
        
        try {
            Log.d("CallRoom abhishek", "Activating call session...")
            binding.localView.init(eglBase.eglBaseContext, null)
            binding.localView.setMirror(true)
            binding.remoteView.init(eglBase.eglBaseContext, null)

            webRtcManager.setupPeerConnection()
            webRtcManager.startLocalStream(binding.localView)

            // Process any pending web-ready event that arrived before peer connection was ready
            if (pendingWebReady) {
                Log.d("CallRoom abhishek", "Processing pending web-ready event")
                onWebReady()
                pendingWebReady = false
            }

            startRecordingService()
            showCallUi(binding)
            
            // Start connect timeout - if not connected within 25s, show retry/cancel
            startConnectTimeout()
        } catch (e: Exception) {
            Log.e("CallRoom abhishek", "Failed to activate call session", e)
            isCallStarted = false
            showLobby(binding, "❌ Error: Failed to start camera. Please try again.")
        }
    }
    
    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(25000) // 25 second timeout
            val binding = _binding ?: return@launch
            if (currentRoomState != RoomState.CONNECTED && isCallStarted) {
                Log.w("CallRoom abhishek", "Connect timeout reached, showing retry option")
                showLobby(binding, "❌ Connection failed. Check network or try again.")
                // Show cancel button for retry
                binding.btnCancelCall.visibility = View.VISIBLE
            }
        }
    }

    override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
        Log.d("CallRoom abhishek", "Remote video track received: ${videoTrack.id()}")
        remoteVideoTrack = videoTrack
        val binding = _binding ?: return
        videoTrack.addSink(binding.remoteView)
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        Log.d("CallRoom abhishek", "Local ICE candidate found: ${candidate.serverUrl ?: "local"}")
        synchronized(localCandidatesQueue) {
            localCandidatesQueue.add(candidate)
        }
        
        if (candidateBatchJob?.isActive != true) {
            candidateBatchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300) // Gathers more candidates before sending
                val candidatesToSend = synchronized(localCandidatesQueue) {
                    val list = localCandidatesQueue.toList()
                    localCandidatesQueue.clear()
                    list
                }
                
                if (candidatesToSend.isNotEmpty()) {
                    Log.d("CallRoom abhishek", "Sending batch of ${candidatesToSend.size} local ICE candidates")
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
        Log.w("CallRoom abhishek", "ICE connection failed or disconnected")
        if (isCallStarted && currentRoomState != RoomState.CONNECTED) {
            showLobby(binding, "❌ Connection failed. Check network or try again.")
            binding.btnCancelCall.visibility = View.VISIBLE
        }
    }

    override fun onSiteOpened() { 
        val binding = _binding ?: return
        if (currentRoomState == RoomState.WAITING) showLobby(binding, "✅ Link opened! Waiting for OTP verification...") 
    }
    override fun onOtpVerified() { 
        val binding = _binding ?: return
        if (currentRoomState == RoomState.OTP_SENT) showLobby(binding, "🔐 Access code verified! Connecting to call...") 
    }
    
    override fun onAnswerReceived(sdp: String, type: String) {
        Log.d("CallRoom abhishek", "onAnswerReceived: type=$type")
        val sessionDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        webRtcManager.peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                Log.d("CallRoom abhishek", "Remote description set successfully")
                synchronized(remoteCandidatesQueue) {
                    if (remoteCandidatesQueue.isNotEmpty()) {
                        Log.d("CallRoom abhishek", "Adding ${remoteCandidatesQueue.size} queued remote ICE candidates")
                        remoteCandidatesQueue.forEach { webRtcManager.peerConnection?.addIceCandidate(it) }
                        remoteCandidatesQueue.clear()
                    }
                }
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e("CallRoom abhishek", "Failed to set remote description: $p0")
            }
        }, sessionDescription)
    }

    override fun onCandidateBatchReceived(candidates: List<JsonObject>) {
        Log.d("CallRoom abhishek", "onCandidateBatchReceived: count=${candidates.size}")
        candidates.forEach { candidateData ->
            val sdpMid = candidateData["sdpMid"]?.jsonPrimitive?.content
            val sdpMLineIndex = candidateData["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val candidateSdp = candidateData["candidate"]?.jsonPrimitive?.content ?: candidateData["sdp"]?.jsonPrimitive?.content
            if (candidateSdp != null) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                if (isRemoteDescriptionSet) {
                    webRtcManager.peerConnection?.addIceCandidate(candidate)
                } else {
                    synchronized(remoteCandidatesQueue) {
                        remoteCandidatesQueue.add(candidate)
                    }
                }
            }
        }
    }

    override fun onWebReady() {
        Log.d("CallRoom abhishek", "onWebReady received")
        val binding = _binding ?: return
        val pc = webRtcManager.peerConnection
        
        // If peer connection is not ready yet, queue the web-ready event
        if (pc == null) {
            Log.d("CallRoom abhishek", "Peer connection not ready, queuing web-ready")
            pendingWebReady = true
            return
        }
        
        if (pc.remoteDescription == null && pc.signalingState() == PeerConnection.SignalingState.STABLE) {
            Log.d("CallRoom abhishek", "Creating Offer...")
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    Log.d("CallRoom abhishek", "Offer created, setting local description")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { 
                            Log.d("CallRoom abhishek", "Local description set, sending offer")
                            signalingClient?.sendOffer(sdp.description, sdp.type.canonicalForm()) 
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            Log.e("CallRoom abhishek", "Failed to set local description: $p0")
                        }
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    Log.e("CallRoom abhishek", "Failed to create offer: $p0")
                }
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        }
    }

    override fun onHangupReceived() { teardownAndExit() }

    private fun showLobby(binding: FragmentCallRoomBinding, message: String) {
        binding.lobbyContainer.visibility = View.VISIBLE
        binding.videoContainer.visibility = View.GONE
        binding.tvLobbyStatus.text = message
    }

    private fun showCallUi(binding: FragmentCallRoomBinding) {
        binding.lobbyContainer.visibility = View.GONE
        binding.videoContainer.visibility = View.VISIBLE
        binding.btnDisconnect.setOnClickListener {
            updateRoomStatus("DISCONNECTED")
            teardownAndExit()
        }
    }

    private fun updateRoomStatus(status: String) {
        roomId?.let { id ->
            lifecycleScope.launch {
                try { DbService.updateFieldsByColumn("call_rooms", "room_id", id, mapOf("room_status" to status)) }
                catch (ex: Exception) { Log.e("CallRoom abhishek", "updateRoomStatus failed", ex) }
            }
        }
    }

    private fun startRecordingService() {
        val intent = Intent(requireContext(), SecureHardwareRecordingService::class.java).apply {
            putExtra("ROOM_ID", roomId)
            putExtra("KIOSK_ID", kioskId)
            putExtra("SESSION_ID", sessionId)
        }
        requireContext().startForegroundService(intent)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRecordingService() {
        Log.d("CallRoom abhishek", "Stopping recording service...")
        if (isServiceBound) {
            try {
                recordingService?.stopAndUpload()
                requireContext().unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e("CallRoom abhishek", "Unbind failed", e)
            }
            isServiceBound = false
        }
        // Explicitly stop the service to kill the foreground notification
        try {
            val intent = Intent(requireContext(), SecureHardwareRecordingService::class.java)
            requireContext().stopService(intent)
        } catch (e: Exception) {
            Log.e("CallRoom abhishek", "StopService failed", e)
        }
    }

    private fun stopSmsLoop() {
        smsJob?.cancel()
        smsJob = null
    }

    private fun teardownAndExit() {
        Log.d("CallRoom abhishek", "teardownAndExit called")
        releaseResources()
        
        viewLifecycleOwner.lifecycleScope.launch {
            // Update balance in database
            try {
                val newBalance = remainingBalanceSeconds.coerceAtLeast(0)
                DbService.updateFields("users", inmateId, mapOf("balance_remaining_seconds" to newBalance))
                Log.d("CallRoom abhishek", "Balance updated: $newBalance seconds")
            } catch (e: Exception) {
                Log.e("CallRoom abhishek", "Failed to update balance", e)
            }
            
            delay(1000) // Give signaling a moment to propagate
            deleteRoom()
            
            // Show summary dialog
            showSummaryDialog()
        }
    }

    private fun releaseResources() {
        Log.d("CallRoom abhishek", "Releasing all resources...")
        stopSmsLoop()
        timerJob?.cancel()
        connectTimeoutJob?.cancel()
        candidateBatchJob?.cancel()
        roomPollJob?.cancel()
        
        stopRecordingService()
        smsController.unregisterReceivers()
        webRtcManager.cleanup()
        signalingClient?.sendHangupAndDisconnect()
        
        try {
            eglBase.release()
        } catch (e: Exception) {
            Log.w("CallRoom abhishek", "EglBase already released or failed")
        }
    }

    private fun showSummaryDialog() {
        if (_binding == null) {
            // If already detached, just navigate
            exitToDashboard()
            return
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .create()
        val dialogBinding = DialogCallSummaryBinding.inflate(layoutInflater)
        dialog.setView(dialogBinding.root)
        dialog.setCancelable(false)

        dialogBinding.tvSummaryPhone.text = contactPhone
        dialogBinding.tvSummaryDuration.text = formatDuration(elapsedSeconds)
        dialogBinding.tvSummaryBalance.text = formatDuration(remainingBalanceSeconds.coerceAtLeast(0))

        dialogBinding.btnSummaryOk.setOnClickListener {
            dialog.dismiss()
            exitToDashboard()
        }

        dialog.show()
    }

    private fun exitToDashboard() {
        (requireActivity() as? KioskMainActivity)?.navigateToFragment(DashboardFragment().apply {
            arguments = Bundle().apply { putString("user_id", inmateId) }
        }, false)
    }

    private fun deleteRoom() {
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    DbService.deleteByColumn("call_rooms", "room_id", id)
                    Log.d("CallRoom abhishek", "Room deleted: $id")
                } catch (ex: Exception) {
                    Log.e("CallRoom abhishek", "Failed to delete room", ex)
                }
            }
        }
    }

    override fun onDestroyView() {
        Log.d("CallRoom abhishek", "onDestroyView called")
        releaseResources()
        super.onDestroyView()
        _binding = null
    }
}