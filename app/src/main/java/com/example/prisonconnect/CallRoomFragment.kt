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
        val inmateId = arguments?.getString("user_id") ?: "INMATE_001"
        roomId = arguments?.getString("room_id") ?: "ROOM_${System.currentTimeMillis()}"
        contactPhone = arguments?.getString("phone_number") ?: ""

        Log.d("CallRoom", "onViewCreated: roomId=$roomId callType=$callType inmateId=$inmateId contactPhone=$contactPhone")

        initializeLobbyUi()
        // First create the room, then start observing
        lifecycleScope.launch {
            initializeRoom(inmateId, callType)
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
                                showLobby("Waiting for terminal response...")
                                stopSignalingPoll()
                                stopRecordingService()
                                // Start SMS loop only if we have a receiver phone AND permission
                                if (!receiverPhone.isNullOrBlank() && hasSmsPermission) {
                                    if (smsJob?.isActive != true) {
                                        startSmsLoop(receiverPhone)
                                    }
                                } else if (!receiverPhone.isNullOrBlank() && !hasSmsPermission) {
                                    Log.w("CallRoom", "Have receiver phone but no SMS permission")
                                }
                            }
                            "OTP_SENT" -> {
                                currentRoomState = RoomState.OTP_SENT
                                showLobby("Link clicked. Awaiting verification...")
                                stopSmsLoop()
                            }
                            "ACTIVE" -> {
                                currentRoomState = RoomState.ACTIVE
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
        Log.d("CallRoom", "startSmsLoop: phone=$phone hasSmsPermission=$hasSmsPermission")
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
            while (isActive && currentRoomState == RoomState.WAITING) {
                try {
                    val smsLink = "PrisonConnect call link: https://prisonconnect-call.rf.gd/join/$roomId?token=$roomToken"
                    Log.d("CallRoom", "sending SMS to $phone with token=$roomToken")
                    SmsManager.getDefault().sendTextMessage(
                        phone,
                        null,
                        smsLink,
                        null,
                        null
                    )
                    Log.d("CallRoom", "SMS invitation sent to $phone")
                } catch (ex: Exception) {
                    Log.e("CallRoom", "SMS send failed to $phone", ex)
                }
                delay(30_000)
            }
            Log.d("CallRoom", "SMS loop ended: currentRoomState=$currentRoomState")
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
        // Do NOT create offer here — wait for web-ready event
        listenForRealtimeSignaling()
        startRecordingService()
        showCallUi()
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
            sdpSemantics = PeerConnection.SdpSemantics.PLAN_B
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }

            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.addSink(binding.remoteView)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    updateRoomStatus("CONNECTED")
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    private fun startLocalStream() {
        val audioSource = factory?.createAudioSource(MediaConstraints())
        localAudioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)

        videoCapturer = createVideoCapturer()
        val videoSource = factory?.createVideoSource(false)

        videoCapturer?.initialize(
            SurfaceTextureHelper.create("VideoCapturerThread", eglBase.eglBaseContext),
            requireContext(),
            videoSource?.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
        localVideoTrack?.addSink(binding.localView)

        val stream = factory?.createLocalMediaStream("ARDAMS")
        stream?.addTrack(localAudioTrack)
        stream?.addTrack(localVideoTrack)
        peerConnection?.addStream(stream)
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
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    // Candidate payload structure wrapper setup
                    val payloadJson = buildJsonObject {
                        put("sender", "android")
                        putJsonObject("candidate") {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("sdp", candidate.sdp)
                        }
                    }

                    val candidateMessage = buildJsonObject {
                        put("sender", "android")
                        putJsonObject("candidate") {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("sdp", candidate.sdp)
                        }
                    }
                    realtimeChannel?.broadcast(event = "candidate", message = candidateMessage)
                    Log.d("CallRoom", "ICE candidate sent via Realtime")
                } catch (ex: Exception) {
                    Log.e("CallRoom", "sendIceCandidate failed", ex)
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

                // Listen for web-ready event
                val webReadyFlow = realtimeChannel?.broadcastFlow<String>("web-ready")
                lifecycleScope.launch {
                    webReadyFlow?.collect { payload ->
                        Log.d("CallRoom", "Received web-ready from web client")
                        if (peerConnection?.remoteDescription == null) {
                            createOffer()
                        }
                    }
                }

                // Listen for answer event
                val answerFlow = realtimeChannel?.broadcastFlow<String>("answer")
                lifecycleScope.launch {
                    answerFlow?.collect { payload ->
                        Log.d("CallRoom", "Received answer via Realtime")
                        try {
                            val answerMap = payload as? Map<*, *>
                            if (answerMap != null) {
                                val typeStr = (answerMap["type"] as? String) ?: ""
                                val sdpStr = (answerMap["sdp"] as? String) ?: ""

                                val sdp = SessionDescription(
                                    SessionDescription.Type.fromCanonicalForm(typeStr),
                                    sdpStr
                                )
                                peerConnection?.setRemoteDescription(object : SdpObserver {
                                    override fun onSetSuccess() {}
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

                // Listen for candidate event
                val candidateFlow = realtimeChannel?.broadcastFlow<Map<String, Any>>("candidate")
                lifecycleScope.launch {
                    candidateFlow?.collect { payload ->
                        Log.d("CallRoom", "Received candidate via Realtime")
                        try {
                            val candidateData = payload["candidate"] as? Map<*, *>
                            if (candidateData != null && peerConnection?.remoteDescription != null) {
                                val sdpMid = candidateData["sdpMid"] as String?
                                val sdpMLineIndex = (candidateData["sdpMLineIndex"] as Number?)?.toInt() ?: 0
                                val sdp = candidateData["sdp"] as String?

                                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                                peerConnection?.addIceCandidate(candidate)
                            }
                        } catch (e: Exception) {
                            Log.e("CallRoom", "Failed to parse ICE candidate", e)
                        }
                    }
                }

                // Listen for hangup event
                val hangupFlow = realtimeChannel?.broadcastFlow<String>("hangup")
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
        peerConnection?.close()
        peerConnection?.dispose()
        factory?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        binding.localView.release()
        binding.remoteView.release()
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