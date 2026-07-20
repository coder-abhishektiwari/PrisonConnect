package com.example.prisonconnect

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.prisonconnect.config.SupabaseConfig
import com.example.prisonconnect.databinding.FragmentCallRoomBinding
import com.example.prisonconnect.model.CallRoom
import com.example.prisonconnect.repository.DbService
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import org.webrtc.*
import java.util.*

class CallRoomFragment : Fragment() {

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

    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    private var recordingService: SecureHardwareRecordingService? = null
    private var isServiceBound = false
    private var roomPollJob: Job? = null
    private var signalingPollJob: Job? = null
    private var smsJob: Job? = null
    private var realtimeChannel: RealtimeChannel? = null
    private var isCallStarted = false
    private var currentRoomState = RoomState.UNKNOWN
    private var hasSmsPermission = false
    private var hasCameraAudioPermission = false
    private var smsSentReceiver: BroadcastReceiver? = null
    private var smsDeliveredReceiver: BroadcastReceiver? = null
    private var pendingCandidates = mutableListOf<IceCandidate>()
    private var candidateBatchJob: Job? = null
    private var isRemoteDescriptionSet = false

    private val eglBase = EglBase.create()

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
    ) { permissions ->
        hasCameraAudioPermission = permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true
        hasSmsPermission = permissions[Manifest.permission.SEND_SMS] == true

        Log.d("CallRoom", "permissionLauncher: cameraAudio=$hasCameraAudioPermission sms=$hasSmsPermission")

        // If SMS permission granted and room is waiting, start SMS loop
        if (hasSmsPermission && currentRoomState == RoomState.WAITING && smsJob?.isActive != true) {
            lifecycleScope.launch {
                try {
                    val receiverPhone = roomId?.let { 
                        val room = DbService.getDocumentByColumn<CallRoom>("call_rooms", "room_id", it)
                        room?.receiver_phone ?: room?.receiverPhone
                    }
                    if (!receiverPhone.isNullOrBlank()) {
                        Log.d("CallRoom", "Starting SMS loop after permission granted")
                        startSmsLoop(receiverPhone)
                    }
                } catch (e: Exception) {
                    Log.e("CallRoom", "Failed to get receiver phone", e)
                }
            }
        }

        if (currentRoomState == RoomState.ACTIVE && !isCallStarted && hasCameraAudioPermission) {
            activateCallSession()
        }
    }

    private enum class RoomState {
        WAITING,
        OTP_SENT,
        ACTIVE,
        CONNECTED,
        DISCONNECTED,
        TAMPER_KILLED,
        UNKNOWN
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallRoomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callType = arguments?.getString("call_type") ?: "VIDEO"
        inmateId = arguments?.getString("user_id") ?: "INMATE_001"
        inmateName = arguments?.getString("inmate_name") ?: arguments?.getString("full_name") ?: "Inmate"
        roomId = arguments?.getString("room_id") ?: "ROOM_${System.currentTimeMillis()}"
        contactPhone = arguments?.getString("phone_number") ?: ""

        Log.d("CallRoom", "onViewCreated: roomId=$roomId callType=$callType inmateId=$inmateId inmateName=$inmateName contactPhone=$contactPhone")

        initializeLobbyUi()
        // First create the room, then start observing
        lifecycleScope.launch {
            // Wait for room to be created
            initializeRoom(inmateId, callType)
            
            // Verify room was created before proceeding
            delay(1000) // Give database time to sync
            val room = DbService.getDocumentByColumn<CallRoom>("call_rooms", "room_id", roomId.toString())
            if (room == null) {
                Log.e("CallRoom", "Room not found after creation: $roomId")
                showLobby("❌ Error: Room not created. Please try again.")
                return@launch
            }
            
            Log.d("CallRoom", "Room verified in database: $roomId")
            requestInitialPermissions()
            observeRoomStatus()
        }
    }

    private fun initializeLobbyUi() {
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
            // Generate 6-digit OTP and random session token
            roomOtp = (100000..999999).random().toString()
            roomToken = "sess_${UUID.randomUUID().toString().replace("-", "")}"

            // Check if room already exists
            val existing: CallRoom? = DbService.getDocumentByColumn("call_rooms", "room_id", id)
            if (existing != null) {
                Log.d("CallRoom", "Room already exists, updating: $id")
                DbService.updateFieldsByColumn("call_rooms", "room_id", id, mapOf(
                    "kiosk_id" to kioskId,
                    "inmate_id" to inmateId,
                    "call_type" to callType,
                    "room_status" to "WAITING",
                    "otp" to roomOtp,
                    "token" to roomToken
                ))
            } else {
                // INSERT the room first — UPDATE won't work on non-existent rows
                Log.d("CallRoom", "Creating new room: $id with phone=$contactPhone")
                val roomData: Map<String, Any> = mapOf(
                    "room_id" to id,
                    "kiosk_id" to kioskId,
                    "inmate_id" to inmateId,
                    "call_type" to callType,
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
            }
            Log.d("CallRoom", "initializeRoom: success id=$id otp=$roomOtp token=$roomToken")
        } catch (ex: Exception) {
            Log.e("CallRoom", "initializeRoom failed for id=$id", ex)
        }
    }

    private fun requestInitialPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS
        )

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        Log.d("CallRoom", "requestInitialPermissions: missing=$missing")

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            hasCameraAudioPermission = true
            hasSmsPermission = true
            Log.d("CallRoom", "requestInitialPermissions: all permissions already granted")
        }
    }

    private fun observeRoomStatus() {
        roomId?.let { id ->
            roomPollJob = lifecycleScope.launch {
                // Small delay to ensure room is created before first poll
                delay(500L)
                while (isActive) {
                    try {
                        val room: CallRoom? = DbService.getDocumentByColumn(
                            table = "call_rooms",
                            column = "room_id",
                            value = id
                        )
                        val stateValue = room?.room_status?.uppercase() ?: "UNKNOWN"
                        val receiverPhone = room?.receiver_phone ?: room?.receiverPhone
                        Log.d("CallRoom", "room poll: state=$stateValue receiverPhone=$receiverPhone")

                        when (stateValue) {
                            "WAITING" -> {
                                currentRoomState = RoomState.WAITING
                                showLobby("📱 Call link sent! Waiting for receiver to open...")
                                stopSignalingPoll()
                                stopRecordingService()
                                // Start SMS loop only if we have a receiver phone AND permission
                                if (!receiverPhone.isNullOrBlank() && hasSmsPermission) {
                                    if (smsJob?.isActive != true) {
                                        startSmsLoop(receiverPhone)
                                    }
                                } else if (!receiverPhone.isNullOrBlank() && !hasSmsPermission) {
                                    Log.w("CallRoom", "Have receiver phone but SMS permission not granted yet")
                                }
                            }
                            "OTP_SENT" -> {
                                currentRoomState = RoomState.OTP_SENT
                                showLobby("✅ Link opened! Sending access code...")
                                Log.d("CallRoom", "Room status changed to OTP_SENT, sending OTP SMS")
                                // Send OTP via SMS when link is clicked
                                val receiverPhone = room?.receiver_phone ?: room?.receiverPhone
                                Log.d("CallRoom", "Receiver phone from room: $receiverPhone")
                                
                                if (!receiverPhone.isNullOrBlank() && hasSmsPermission) {
                                    Log.d("CallRoom", "Calling sendOtpSms with phone: $receiverPhone")
                                    sendOtpSms(receiverPhone)
                                    showLobby("✅ Access code sent! Waiting for verification...")
                                } else {
                                    Log.w("CallRoom", "Cannot send OTP: phone=$receiverPhone, permission=$hasSmsPermission")
                                    showLobby("⚠️ Cannot send access code. Please try again.")
                                }
                                stopSmsLoop()
                            }
                            "ACTIVE" -> {
                                currentRoomState = RoomState.ACTIVE
                                showLobby("🔐 Access code verified! Connecting to call...")
                                stopSmsLoop()
                                if (!isCallStarted) {
                                    if (hasCameraAudioPermission) {
                                        activateCallSession()
                                    } else {
                                        requestInitialPermissions()
                                    }
                                }
                            }
                            "CONNECTED" -> {
                                currentRoomState = RoomState.CONNECTED
                                showLobby("✅ Call connected!")
                                // WebRTC connected — stay in call UI
                            }
                            "DISCONNECTED" -> {
                                currentRoomState = RoomState.DISCONNECTED
                                teardownAndExit()
                            }
                            "TAMPER_KILLED" -> {
                                currentRoomState = RoomState.TAMPER_KILLED
                                teardownAndExit()
                            }
                            else -> {
                                currentRoomState = RoomState.UNKNOWN
                                showLobby("Waiting for terminal response...")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CallRoom", "Room polling failed", e)
                    }
                    delay(3000L)
                }
            }
        }
    }

    private fun showLobby(message: String) {
        binding.lobbyContainer.visibility = View.VISIBLE
        binding.videoContainer.visibility = View.GONE
        binding.remoteView.visibility = View.GONE
        binding.localView.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE
        binding.btnDisconnect.visibility = View.GONE
        binding.tvLobbyStatus.text = message
        binding.lobbyProgress.visibility = View.VISIBLE
    }

    private fun showCallUi() {
        binding.lobbyContainer.visibility = View.GONE
        binding.videoContainer.visibility = View.VISIBLE
        binding.remoteView.visibility = View.VISIBLE
        binding.localView.visibility = View.VISIBLE
        binding.recordingIndicator.visibility = View.VISIBLE
        binding.btnDisconnect.visibility = View.VISIBLE
    }

    private fun startSmsLoop(phone: String) {
        Log.d("CallRoom", "startSmsLoop: phone=$phone hasSmsPermission=$hasSmsPermission inmateName=$inmateName")
        
        // Validate phone number
        if (phone.isBlank() || phone.length < 10) {
            Log.e("CallRoom", "Invalid phone number: $phone")
            return
        }
        
        if (smsJob?.isActive == true) {
            Log.d("CallRoom", "startSmsLoop skipped: job already active")
            return
        }
        
        if (!hasSmsPermission) {
            Log.w("CallRoom", "startSmsLoop skipped: missing SEND_SMS permission")
            return
        }

        smsJob = lifecycleScope.launch {
            Log.d("CallRoom", "SMS loop started for phone=$phone")
            
            // Send link SMS only once
            try {
                // Use correct URL format that works with .htaccess: join.html?room=ROOM_ID&token=TOKEN
                val smsLink = "https://prisonconnect-call.rf.gd/index.html?room=$roomId&token=$roomToken"
                val linkMessage = "PrisonConnect: You have a video call request from " +
                        "Inmate: $inmateName " +
                        "at ${kioskId}. " +
                        "Join: $smsLink"
                
                Log.d("CallRoom", "Attempting to send link SMS to $phone with inmateName=$inmateName")
                Log.d("CallRoom", "SMS Link: $smsLink")
                sendSmsWithDeliveryTracking(phone, linkMessage, "LINK")
                Log.d("CallRoom", "Link SMS sent successfully to $phone")
            } catch (ex: Exception) {
                Log.e("CallRoom", "SMS send failed to $phone", ex)
            }
            
            // Wait for room state to change (don't resend link)
            while (isActive && currentRoomState == RoomState.WAITING) {
                delay(1000)
            }
            Log.d("CallRoom", "SMS loop ended: currentRoomState=$currentRoomState")
        }
    }
    
    private fun sendOtpSms(phone: String) {
        Log.d("CallRoom", "sendOtpSms: phone=$phone hasSmsPermission=$hasSmsPermission roomOtp=$roomOtp")
        
        // Validate phone number
        if (phone.isBlank() || phone.length < 10) {
            Log.e("CallRoom", "Invalid phone number for OTP: $phone")
            return
        }
        
        if (!hasSmsPermission) {
            Log.w("CallRoom", "sendOtpSms skipped: missing SEND_SMS permission")
            return
        }
        
        if (roomOtp.isBlank()) {
            Log.e("CallRoom", "sendOtpSms skipped: OTP is empty")
            return
        }
        
        try {
            val otpMessage = "PrisonConnect: Your access code is: $roomOtp. " +
                    "Enter this code on the website to join the call."
            
            Log.d("CallRoom", "Attempting to send OTP SMS to $phone with OTP=$roomOtp")
            sendSmsWithDeliveryTracking(phone, otpMessage, "OTP")
            Log.d("CallRoom", "OTP SMS queued successfully to $phone")
            
            // Update UI to show OTP sent
            showLobby("✅ Access code sent! Please check your messages.")
        } catch (ex: Exception) {
            Log.e("CallRoom", "OTP SMS send failed to $phone", ex)
            showLobby("❌ Failed to send access code. Please try again.")
        }
    }
    
    private fun sendSmsWithDeliveryTracking(phone: String, message: String, type: String) {
        val smsManager = SmsManager.getDefault()
        
        // Create pending intents for tracking
        val sentIntent = PendingIntent.getBroadcast(
            requireContext(),
            0,
            Intent("SMS_SENT").putExtra("type", type),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val deliveryIntent = PendingIntent.getBroadcast(
            requireContext(),
            0,
            Intent("SMS_DELIVERED").putExtra("type", type),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Unregister previous receivers if any
        try {
            if (smsSentReceiver != null) {
                requireContext().unregisterReceiver(smsSentReceiver)
            }
            if (smsDeliveredReceiver != null) {
                requireContext().unregisterReceiver(smsDeliveredReceiver)
            }
        } catch (e: Exception) {
            // Receivers might not be registered yet
        }
        
        // Create and register broadcast receivers for tracking
        smsSentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("CallRoom", "$type SMS sent callback received, resultCode=$resultCode")
                when (resultCode) {
                    -1 -> {
                        Log.d("CallRoom", "$type SMS sent successfully")
                    }
                    1 -> {
                        Log.e("CallRoom", "$type SMS failed: Generic failure")
                    }
                    2 -> {
                        Log.e("CallRoom", "$type SMS failed: No service")
                    }
                    3 -> {
                        Log.e("CallRoom", "$type SMS failed: Null PDU")
                    }
                    4 -> {
                        Log.e("CallRoom", "$type SMS failed: Radio off")
                    }
                }
            }
        }
        
        smsDeliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("CallRoom", "$type SMS delivery callback received, resultCode=$resultCode")
                when (resultCode) {
                    -1 -> {
                        Log.d("CallRoom", "$type SMS delivered successfully")
                    }
                    else -> {
                        Log.w("CallRoom", "$type SMS delivery failed")
                    }
                }
            }
        }
        
        // Register receivers
        requireContext().registerReceiver(smsSentReceiver, IntentFilter("SMS_SENT"))
        requireContext().registerReceiver(smsDeliveredReceiver, IntentFilter("SMS_DELIVERED"))
        
        try {
            // Split message if too long
            val messageParts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()
            
            repeat(messageParts.size) {
                sentIntents.add(sentIntent)
                deliveryIntents.add(deliveryIntent)
            }
            
            smsManager.sendMultipartTextMessage(
                phone,
                null,
                messageParts,
                sentIntents,
                deliveryIntents
            )
            
            Log.d("CallRoom", "$type SMS queued for sending to $phone (${messageParts.size} parts)")
        } catch (ex: Exception) {
            Log.e("CallRoom", "$type SMS send failed: ${ex.message}")
            throw ex
        }
    }

    private fun activateCallSession() {
        if (isCallStarted) return
        isCallStarted = true
        stopSmsLoop()
        setupSurfaceViews()
        initializeWebRTC()
        setupPeerConnection()
        startLocalStream()
        listenForRealtimeSignaling()
        startRecordingService()
        showCallUi()
        // Do NOT create offer here — wait for web-ready event from web side
        Log.d("CallRoom", "Call session activated, waiting for web-ready signal")
    }

    private fun setupSurfaceViews() {
        binding.localView.init(eglBase.eglBaseContext, null)
        binding.localView.setMirror(true)
        binding.localView.setEnableHardwareScaler(true)

        binding.remoteView.init(eglBase.eglBaseContext, null)
        binding.remoteView.setEnableHardwareScaler(true)
    }

    private fun initializeWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(requireContext())
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun setupPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // Use UNIFIED_PLAN to match web browser's default
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                Log.d("CallRoom", "Remote track added: ${receiver.track()?.kind()}")
                val videoTrack = streams.flatMap { it.videoTracks }.firstOrNull()
                videoTrack?.addSink(binding.remoteView)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d("CallRoom", "ICE connection state: $newState")
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    updateRoomStatus("CONNECTED")
                    Log.d("CallRoom", "ICE connection established!")
                } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                    Log.e("CallRoom", "ICE connection failed!")
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                Log.d("CallRoom", "Signaling state: ${p0 ?: "null"}")
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(p0: MediaStream?) {}
        })
    }

    private fun startLocalStream() {
        val audioSource = factory?.createAudioSource(MediaConstraints())
        localAudioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)

        videoCapturer = createVideoCapturer()
        val videoSource = factory?.createVideoSource(false)

        videoCapturer?.initialize(
            SurfaceTextureHelper.create("VideoCapturerThread", eglBase.eglBaseContext),
            requireContext(),
            videoSource?.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
        localVideoTrack?.setEnabled(true)
        localVideoTrack?.addSink(binding.localView)

        // Add tracks directly to peer connection (new API)
        peerConnection?.addTrack(localAudioTrack)
        peerConnection?.addTrack(localVideoTrack)
        
        Log.d("CallRoom", "Local audio and video tracks added to peer connection")
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(requireContext())
        return enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendOffer(sdp)
                    }
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

    private fun sendOffer(sdp: SessionDescription) {
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    // Map ko explicit kotlinx.serialization.json structure me transform karein
                    val payloadJson = buildJsonObject {
                        put("sender", "android")
                        putJsonObject("offer") {
                            put("type", sdp.type.canonicalForm())
                            put("sdp", sdp.description)
                        }
                    }

                    val offerMessage = buildJsonObject {
                        put("sender", "android")
                        putJsonObject("offer") {
                            put("type", sdp.type.canonicalForm())
                            put("sdp", sdp.description)
                        }
                    }
                    realtimeChannel?.broadcast(event = "offer", message = offerMessage)
                    Log.d("CallRoom", "Offer sent via Realtime")
                } catch (ex: Exception) {
                    Log.e("CallRoom", "sendOffer failed", ex)
                }
            }
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        // Add candidate to batch instead of sending immediately
        pendingCandidates.add(candidate)
        Log.d("CallRoom", "ICE candidate queued (${pendingCandidates.size} pending)")
        
        // Start batch job if not already running
        if (candidateBatchJob?.isActive != true) {
            candidateBatchJob = lifecycleScope.launch {
                // Wait 100ms to collect more candidates
                delay(100)
                
                // Send all pending candidates in one batch
                if (pendingCandidates.isNotEmpty()) {
                    val candidatesToSend = pendingCandidates.toList()
                    pendingCandidates.clear()
                    
                    try {
                        // Convert to JsonArray properly
                        val candidatesJsonArray = JsonArray(candidatesToSend.map { c ->
                            buildJsonObject {
                                put("sdpMid", c.sdpMid ?: "")
                                put("sdpMLineIndex", c.sdpMLineIndex)
                                put("sdp", c.sdp)
                            }
                        })
                        
                        val batchMessage = buildJsonObject {
                            put("sender", "android")
                            put("candidates", candidatesJsonArray)
                        }
                        
                        realtimeChannel?.broadcast(event = "candidates-batch", message = batchMessage)
                        Log.d("CallRoom", "Sent ${candidatesToSend.size} ICE candidates via Realtime")
                    } catch (ex: Exception) {
                        Log.e("CallRoom", "sendIceCandidate batch failed", ex)
                    }
                }
            }
        }
    }

    private fun listenForRealtimeSignaling() {
        val id = roomId ?: return
        signalingPollJob = lifecycleScope.launch {
            try {
                // Create channel subscription
                realtimeChannel = SupabaseConfig.client.channel("room_$id")

                // Listen for site-opened event (web client opened the link)
                val siteOpenedFlow = realtimeChannel?.broadcastFlow<JsonObject>("site-opened")
                lifecycleScope.launch {
                    siteOpenedFlow?.collect { payload ->
                        Log.d("CallRoom", "Website opened by receiver")
                        // Update UI to show link was opened
                        if (currentRoomState == RoomState.WAITING) {
                            showLobby("✅ Link opened! Waiting for OTP verification...")
                        }
                    }
                }

                // Listen for otp-verified event (web client verified OTP)
                val otpVerifiedFlow = realtimeChannel?.broadcastFlow<JsonObject>("otp-verified")
                lifecycleScope.launch {
                    otpVerifiedFlow?.collect { payload ->
                        Log.d("CallRoom", "OTP verified by receiver")
                        // Update UI to show OTP was verified
                        if (currentRoomState == RoomState.OTP_SENT) {
                            showLobby("🔐 Access code verified! Connecting to call...")
                        }
                    }
                }

                // Listen for web-ready event (web client is ready to receive offer)
                val webReadyFlow = realtimeChannel?.broadcastFlow<JsonObject>("web-ready")
                lifecycleScope.launch {
                    webReadyFlow?.collect { payload ->
                        Log.d("CallRoom", "Web client is ready, creating offer")
                        // Create offer when web client signals they're ready
                        if (peerConnection?.remoteDescription == null && peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
                            createOffer()
                        }
                    }
                }

                // Listen for answer event
                val answerFlow = realtimeChannel?.broadcastFlow<JsonObject>("answer")
                lifecycleScope.launch {
                    answerFlow?.collect { payload ->
                        Log.d("CallRoom", "Received answer via Realtime")
                        try {
                            val answerData = payload["answer"] as? JsonObject
                            if (answerData != null) {
                                val typeStr = answerData["type"]?.jsonPrimitive?.content ?: ""
                                val sdpStr = answerData["sdp"]?.jsonPrimitive?.content ?: ""

                                val sdp = SessionDescription(
                                    SessionDescription.Type.fromCanonicalForm(typeStr),
                                    sdpStr
                                )
                                peerConnection?.setRemoteDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        Log.d("CallRoom", "Answer set successfully")
                                        isRemoteDescriptionSet = true
                                        
                                        // Now add any pending candidates
                                        if (pendingCandidates.isNotEmpty()) {
                                            Log.d("CallRoom", "Adding ${pendingCandidates.size} pending candidates")
                                            val candidatesToAdd = pendingCandidates.toList()
                                            pendingCandidates.clear()
                                            
                                            for (candidate in candidatesToAdd) {
                                                try {
                                                    peerConnection?.addIceCandidate(candidate)
                                                } catch (e: Exception) {
                                                    Log.e("CallRoom", "Failed to add pending candidate", e)
                                                }
                                            }
                                        }
                                    }
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {}
                                }, sdp)
                            }
                        } catch (e: Exception) {
                            Log.e("CallRoom", "Failed to parse WebRTC answer", e)
                        }
                    }
                }

                // Listen for candidates-batch event (batched ICE candidates from web)
                val candidateBatchFlow = realtimeChannel?.broadcastFlow<JsonObject>("candidates-batch")
                lifecycleScope.launch {
                    candidateBatchFlow?.collect { payload ->
                        Log.d("CallRoom", "Received candidate batch via Realtime")
                        try {
                            val candidatesArray = payload["candidates"] as? List<JsonObject>
                            if (candidatesArray != null) {
                                // If remote description is set, add candidates immediately
                                // Otherwise, queue them for later
                                if (peerConnection?.remoteDescription != null) {
                                    for (candidateData in candidatesArray) {
                                        val sdpMid = candidateData["sdpMid"]?.jsonPrimitive?.content
                                        val sdpMLineIndex = candidateData["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                        val sdp = candidateData["sdp"]?.jsonPrimitive?.content

                                        if (sdp != null) {
                                            val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                                            peerConnection?.addIceCandidate(candidate)
                                        }
                                    }
                                    Log.d("CallRoom", "Added ${candidatesArray.size} candidates from batch")
                                } else {
                                    // Queue candidates for later
                                    Log.d("CallRoom", "Queueing ${candidatesArray.size} candidates (no remote description yet)")
                                    for (candidateData in candidatesArray) {
                                        val sdpMid = candidateData["sdpMid"]?.jsonPrimitive?.content
                                        val sdpMLineIndex = candidateData["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                        val sdp = candidateData["sdp"]?.jsonPrimitive?.content

                                        if (sdp != null) {
                                            val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                                            pendingCandidates.add(candidate)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CallRoom", "Failed to parse ICE candidate batch", e)
                        }
                    }
                }

                // Listen for hangup event
                val hangupFlow = realtimeChannel?.broadcastFlow<JsonObject>("hangup")
                lifecycleScope.launch {
                    hangupFlow?.collect { payload ->
                        Log.d("CallRoom", "Received hangup via Realtime")
                        teardownAndExit()
                    }
                }

                // Subscribe to channel
                realtimeChannel?.subscribe()
                Log.d("CallRoom", "Realtime channel subscribed: room_$id")
            } catch (e: Exception) {
                Log.e("CallRoom", "Failed to subscribe to Realtime channel", e)
            }
        }
    }

    private fun updateRoomStatus(status: String) {
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    DbService.updateFieldsByColumn("call_rooms", "room_id", id, mapOf("room_status" to status))
                } catch (ex: Exception) {
                    Log.e("CallRoom", "updateRoomStatus failed", ex)
                }
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

    private fun teardownAndExit() {
        stopSmsLoop()
        stopRecordingService()
        cleanupWebRTC()
        roomPollJob?.cancel()
        signalingPollJob?.cancel()

        // Send hangup via Realtime before disconnecting
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    val payloadJson = buildJsonObject {
                        put("sender", "android")
                    }
                    val hangupMessage = buildJsonObject {
                        put("sender", "android")
                    }
                    realtimeChannel?.broadcast(event = "hangup", message = hangupMessage)
                    realtimeChannel?.unsubscribe()
                } catch (ex: Exception) {
                    Log.e("CallRoom", "hangup broadcast failed", ex)
                }
            }
        }

        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun stopRecordingService() {
        if (isServiceBound) {
            recordingService?.stopAndUpload()
            requireContext().unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun cleanupWebRTC() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error stopping capturer", e)
        }
        
        try {
            localVideoTrack?.dispose()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error disposing video track", e)
        }
        
        try {
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error disposing audio track", e)
        }
        
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error closing peer connection", e)
        }
        
        try {
            factory?.dispose()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error disposing factory", e)
        }
        
        try {
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error disposing capturer", e)
        }
        
        try {
            binding.localView.release()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error releasing local view", e)
        }
        
        try {
            binding.remoteView.release()
        } catch (e: Exception) {
            Log.e("CallRoom", "Error releasing remote view", e)
        }
        
        peerConnection = null
        factory = null
        localVideoTrack = null
        localAudioTrack = null
        videoCapturer = null
    }

    private fun stopSignalingPoll() {
        signalingPollJob?.cancel()
        signalingPollJob = null
    }

    private fun stopSmsLoop() {
        smsJob?.cancel()
        smsJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSmsLoop()
        roomPollJob?.cancel()
        signalingPollJob?.cancel()

        // Leave Realtime channel
        lifecycleScope.launch {
            try {
                realtimeChannel?.unsubscribe()
            } catch (ex: Exception) {
                Log.e("CallRoom", "Failed to unsubscribe from Realtime channel", ex)
            }
        }

        if (isServiceBound) {
            requireContext().unbindService(serviceConnection)
            isServiceBound = false
        }
        eglBase.release()
        _binding = null
    }
}