package com.example.prisonconnect

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.prisonconnect.databinding.FragmentCallRoomBinding
import com.example.prisonconnect.databinding.DialogCallSummaryBinding
import com.example.prisonconnect.model.CallRoom
import com.example.prisonconnect.model.User
import com.example.prisonconnect.repository.DbService
import com.example.prisonconnect.webrtc.CallDiagnosticHelper
import com.example.prisonconnect.webrtc.SignalingClient
import com.example.prisonconnect.webrtc.SmsController
import com.example.prisonconnect.webrtc.WebRtcManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.webrtc.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fragment for handling both audio and video calls from the kiosk.
 *
 * Manages the complete call lifecycle including:
 * - Room initialization and status polling
 * - Pre-call diagnostics
 * - WebRTC peer connection setup
 * - Signaling via Supabase Realtime
 * - SMS link delivery with OTP
 * - Call timer and balance tracking
 * - Recording service integration
 * - UI for both audio and video call modes
 * - Local video overlay with drag for video calls
 */
class CallRoomFragment : Fragment(),
    WebRtcManager.WebRtcListener,
    SignalingClient.SignalingListener {

    private var _binding: FragmentCallRoomBinding? = null
    private val binding get() = _binding!!

    private val logger = Logger("CallRoom")

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
    private lateinit var diagnosticHelper: CallDiagnosticHelper
    private var signalingClient: SignalingClient? = null

    private var recordingService: SecureHardwareRecordingService? = null
    private var isServiceBound = false
    private var roomPollJob: Job? = null
    private var smsJob: Job? = null
    private var timerJob: Job? = null
    private var isCallStarted = AtomicBoolean(false)
    private var isOtpSent = false
    private var isLinkSent = false
    private var isOfferSent = AtomicBoolean(false)
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

    /** Custom Logger class to avoid confusion with android.util.Log */
    private class Logger(private val tag: String) {
        fun d(message: String) = Log.d(tag, message)
        fun w(message: String) = Log.w(tag, message)
        fun e(message: String, throwable: Throwable? = null) {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    private enum class RoomState {
        WAITING, OTP_SENT, ACTIVE, CONNECTED, DISCONNECTED, TAMPER_KILLED, UNKNOWN
    }

    companion object {
        private const val TAG = "CallRoom"
        private const val ROOM_POLL_INTERVAL_MS = 3000L
        private const val CANDIDATE_BATCH_DELAY_MS = 300L
        private const val CONNECT_TIMEOUT_MS = 25000L
        private const val OVERLAY_HIDE_DELAY_MS = 3000L
        private const val FALLBACK_OFFER_DELAY_MS = 1500L
        private const val BALANCE_SYNC_INTERVAL_SECONDS = 10
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
        if (results.values.all { it }) {
            runPreCallDiagnostic()
        } else {
            showLobby(binding, "❌ Permissions denied. Cannot proceed with the call.")
        }
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

        val safeBinding = _binding ?: return

        smsController = SmsController()
        webRtcManager = WebRtcManager(requireContext(), eglBase, this)
        diagnosticHelper = CallDiagnosticHelper(requireContext())

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            webRtcManager.initialize()
            logger.d("WebRtcManager initialized in background")
        }

        callType = arguments?.getString("call_type") ?: "VIDEO"
        inmateId = arguments?.getString("user_id") ?: "INMATE_001"
        inmateName = arguments?.getString("inmate_name")
            ?: arguments?.getString("full_name") ?: "Inmate"
        jailName = arguments?.getString("jail_name") ?: "jail"
        roomId = arguments?.getString("room_id")
            ?: "ROOM_${System.currentTimeMillis()}"
        contactPhone = arguments?.getString("phone_number") ?: ""

        logger.d(
            "onViewCreated - roomId: $roomId, contactPhone: $contactPhone, " +
                    "inmateName: $inmateName"
        )

        initializeLobbyUi(safeBinding)
        setupBackPressHandler()
        setupVideoDrag()
        setupOverlayLogic()
        setupAudioControls()

        viewLifecycleOwner.lifecycleScope.launch {
            val user = DbService.getDocument<User>("users", inmateId)
            initialBalanceSeconds = user?.balance_remaining_seconds ?: 0L
            remainingBalanceSeconds = initialBalanceSeconds

            initializeRoom(inmateId, callType)

            // Retry finding room in database (up to 5 seconds)
            var room: CallRoom? = null
            for (i in 1..5) {
                delay(1000)
                if (_binding == null) return@launch
                room = DbService.getDocumentByColumn<CallRoom>(
                    "call_rooms",
                    "room_id",
                    roomId.toString()
                )
                if (room != null) {
                    logger.d("Room found after $i seconds")
                    break
                }
            }

            if (_binding == null) return@launch
            if (room == null) {
                showLobby(safeBinding, "❌ Error: Room not created.")
                return@launch
            }

            signalingClient = SignalingClient(
                roomId!!,
                viewLifecycleOwner.lifecycleScope,
                this@CallRoomFragment
            )
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
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    updateRoomStatus("DISCONNECTED")
                    teardownAndExit()
                }
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVideoDrag() {
        val safeBinding = _binding ?: return
        var dX = 0f
        var dY = 0f

        safeBinding.localView.setOnTouchListener { view, event ->
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
        val safeBinding = _binding ?: return
        safeBinding.videoClickOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                toggleOverlays()
            }
            true
        }

        safeBinding.btnVideoHangup.setOnClickListener {
            updateRoomStatus("DISCONNECTED")
            teardownAndExit()
        }

        safeBinding.btnVideoMic.setOnClickListener {
            isMicEnabled = !isMicEnabled
            webRtcManager.setAudioEnabled(isMicEnabled)
            val icon = if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
            safeBinding.btnVideoMic.setIconResource(icon)
            safeBinding.btnAudioMic.setIconResource(icon)
            safeBinding.btnVideoMic.alpha = if (isMicEnabled) 1.0f else 0.6f
            safeBinding.btnAudioMic.alpha = if (isMicEnabled) 1.0f else 0.6f
        }

        safeBinding.btnVideoSwitch.setOnClickListener {
            webRtcManager.switchCamera()
        }
    }

    private fun setupAudioControls() {
        val safeBinding = _binding ?: return
        safeBinding.btnAudioHangup.setOnClickListener {
            updateRoomStatus("DISCONNECTED")
            teardownAndExit()
        }

        safeBinding.btnAudioMic.setOnClickListener {
            isMicEnabled = !isMicEnabled
            webRtcManager.setAudioEnabled(isMicEnabled)
            val icon = if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
            safeBinding.btnAudioMic.setIconResource(icon)
            safeBinding.btnVideoMic.setIconResource(icon)
            safeBinding.btnAudioMic.alpha = if (isMicEnabled) 1.0f else 0.6f
            safeBinding.btnVideoMic.alpha = if (isMicEnabled) 1.0f else 0.6f
        }

        safeBinding.btnAudioSpeaker.setOnClickListener {
            isSpeakerEnabled = !isSpeakerEnabled
            webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
            safeBinding.btnAudioSpeaker.alpha = if (isSpeakerEnabled) 1.0f else 0.5f
        }

        safeBinding.btnAudioInfo.setOnClickListener {
            showCallInfoTooltip()
        }
    }

    private fun showCallInfoTooltip() {
        val info = "Inmate: $inmateName\nNumber: $contactPhone\n" +
                "Duration: ${formatSeconds(elapsedSeconds)}\n" +
                "Balance: ${formatSeconds(remainingBalanceSeconds)}\nFacility: $jailName"
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
        val safeBinding = _binding ?: return
        overlaysVisible = true
        safeBinding.videoHeader.animate().alpha(1f).setDuration(300).start()
        safeBinding.videoFooter.animate().alpha(1f).setDuration(300).start()
        resetOverlayTimer()
    }

    private fun hideOverlays() {
        val safeBinding = _binding ?: return
        overlaysVisible = false
        safeBinding.videoHeader.animate().alpha(0f).setDuration(300).start()
        safeBinding.videoFooter.animate().alpha(0f).setDuration(300).start()
    }

    private fun resetOverlayTimer() {
        hideOverlaysJob?.cancel()
        hideOverlaysJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(OVERLAY_HIDE_DELAY_MS)
            hideOverlays()
        }
    }

    private fun runPreCallDiagnostic() {
        val safeBinding = _binding ?: return
        val isVideoCall = callType.equals("VIDEO", ignoreCase = true)
        logger.d("runPreCallDiagnostic: isVideo=$isVideoCall")
        val result = diagnosticHelper.performFullDiagnostic(isVideo = isVideoCall)
        val plan = diagnosticHelper.getResolutionPlan(result, diagnosticLauncher)

        if (result is CallDiagnosticHelper.DiagnosticResult.Success) {
            if (currentRoomState == RoomState.ACTIVE && !isCallStarted.get()) {
                activateCallSession()
            }
        } else {
            showLobby(safeBinding, "⚠️ ${plan.message}")
            if (plan.actionLabel != null && plan.action != null) {
                safeBinding.btnCancelCall.text = plan.actionLabel
                safeBinding.btnCancelCall.setOnClickListener { plan.action.invoke() }
            }
        }
    }

    private fun initializeLobbyUi(safeBinding: FragmentCallRoomBinding) {
        safeBinding.lobbyContainer.visibility = View.VISIBLE
        safeBinding.videoCallContainer.visibility = View.GONE
        safeBinding.audioCallContainer.visibility = View.GONE
        safeBinding.tvLobbyStatus.text = "Waiting for terminal response..."
        safeBinding.btnCancelCall.setOnClickListener {
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
                "webrtc_signaling" to mapOf(
                    "offer" to null,
                    "answer" to null,
                    "iceCandidates" to emptyList<Map<String, Any>>()
                )
            )
            DbService.insertRaw("call_rooms", roomData)
            logger.d("Room initialized: $id")
        } catch (ex: Exception) {
            logger.e("initRoom failed", ex)
        }
    }

    private fun observeRoomStatus() {
        roomId?.let { id ->
            roomPollJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    if (_binding == null) break
                    try {
                        val room: CallRoom? = DbService.getDocumentByColumn(
                            "call_rooms",
                            "room_id",
                            id
                        )
                        val stateValue = room?.room_status?.uppercase() ?: "UNKNOWN"
                        val receiverPhone = room?.receiver_phone ?: room?.receiverPhone
                        val safeBinding = _binding ?: break

                        logger.d(
                            "Poll: status=$stateValue, " +
                                    "isCallStarted=${isCallStarted.get()}"
                        )

                        when (stateValue) {
                            "WAITING" -> {
                                if (!isCallStarted.get()) {
                                    currentRoomState = RoomState.WAITING
                                    showLobby(safeBinding, "📱 Call link sent!")
                                    if (!receiverPhone.isNullOrBlank() &&
                                        smsJob?.isActive != true
                                    ) {
                                        startSmsLoop(receiverPhone)
                                    }
                                }
                            }
                            "OTP_SENT" -> {
                                if (!isCallStarted.get()) {
                                    currentRoomState = RoomState.OTP_SENT
                                    showLobby(safeBinding, "✅ Link opened! Sending code...")
                                    if (!receiverPhone.isNullOrBlank()) {
                                        sendOtpSms(receiverPhone)
                                    }
                                }
                            }
                            "ACTIVE" -> {
                                if (!isCallStarted.get()) {
                                    currentRoomState = RoomState.ACTIVE
                                    showLobby(safeBinding, "🔐 Access verified! Connecting...")
                                    runPreCallDiagnostic()
                                }
                            }
                            "CONNECTED" -> {
                                if (currentRoomState != RoomState.CONNECTED) {
                                    currentRoomState = RoomState.CONNECTED
                                    showCallUi(safeBinding)
                                    startCallTimer()
                                }
                            }
                            "DISCONNECTED" -> {
                                currentRoomState = RoomState.DISCONNECTED
                                teardownAndExit()
                            }
                            "TAMPER_KILLED" -> {
                                currentRoomState = RoomState.TAMPER_KILLED
                                teardownAndExit()
                            }
                        }
                    } catch (e: Exception) {
                        logger.e("Poll failed", e)
                    }
                    delay(ROOM_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun startCallTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            var syncCounter = 0
            while (isActive && currentRoomState == RoomState.CONNECTED) {
                delay(1000)
                val safeBinding = _binding ?: break
                elapsedSeconds++
                remainingBalanceSeconds--
                syncCounter++

                val timeStr = formatSeconds(elapsedSeconds)
                safeBinding.tvVideoDuration.text = timeStr
                safeBinding.tvVideoBalance.text = "Bal: ${formatSeconds(remainingBalanceSeconds)}"
                safeBinding.tvAudioDuration.text = timeStr

                // Sync balance to Supabase every 10 seconds
                if (syncCounter >= BALANCE_SYNC_INTERVAL_SECONDS) {
                    syncCounter = 0
                    val currentUserId = inmateId
                    val currentBalance = remainingBalanceSeconds
                    if (currentUserId.isNotBlank()) {
                        launch(Dispatchers.IO) {
                            try {
                                DbService.updateUserBalance(currentUserId, currentBalance)
                            } catch (e: Exception) {
                                logger.e("Periodic balance sync failed", e)
                            }
                        }
                    }
                }

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
                val smsLink = "https://prisonconnect-call.rf.gd/index.html" +
                        "?room=$roomId&token=$roomToken"
                val linkMessage = "PrisonConnect: You have a video call request from " +
                        "Inmate: $inmateName at $jailName. Join: $smsLink"
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
        if (!isCallStarted.compareAndSet(false, true)) {
            logger.d("activateCallSession: Already started, skipping")
            return
        }
        val safeBinding = _binding ?: return
        try {
            logger.d("Activating call session (Type: $callType)...")

            val isVideo = callType.equals("VIDEO", ignoreCase = true)

            if (isVideo) {
                safeBinding.localView.visibility = View.VISIBLE
                safeBinding.localView.init(eglBase.eglBaseContext, null)
                safeBinding.localView.setMirror(true)
                safeBinding.localView.setZOrderMediaOverlay(true)
            } else {
                safeBinding.localView.visibility = View.GONE
            }

            safeBinding.remoteView.init(eglBase.eglBaseContext, null)

            webRtcManager.setupPeerConnection(isVideo = isVideo)
            webRtcManager.startLocalStream(safeBinding.localView, isVideo = isVideo)

            if (pendingWebReady) {
                onWebReady()
                pendingWebReady = false
            }

            // Fallback trigger to ensure Offer is sent if web client is slow
            viewLifecycleOwner.lifecycleScope.launch {
                delay(FALLBACK_OFFER_DELAY_MS)
                if (_binding != null && currentRoomState != RoomState.CONNECTED) {
                    logger.d("Fallback: Triggering onWebReady...")
                    onWebReady()
                }
            }

            startRecordingService()
            showCallUi(safeBinding)
            startConnectTimeout()
        } catch (e: Exception) {
            logger.e("Failed to activate call session", e)
            isCallStarted.set(false)
        }
    }

    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val safeBinding = _binding ?: return@launch
            if (currentRoomState != RoomState.CONNECTED && isCallStarted.get()) {
                showLobby(safeBinding, "❌ Connection failed.")
            }
        }
    }

    // --- WebRtcListener Implementation ---

    override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
        remoteVideoTrack = videoTrack
        val safeBinding = _binding ?: return
        videoTrack.addSink(safeBinding.remoteView)
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        synchronized(localCandidatesQueue) { localCandidatesQueue.add(candidate) }
        if (candidateBatchJob?.isActive != true) {
            candidateBatchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(CANDIDATE_BATCH_DELAY_MS)
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

        // Immediate UI transition without waiting for DB poll
        viewLifecycleOwner.lifecycleScope.launch {
            val safeBinding = _binding ?: return@launch
            if (currentRoomState != RoomState.CONNECTED) {
                currentRoomState = RoomState.CONNECTED
                showCallUi(safeBinding)
                startCallTimer()
            }
        }
    }

    override fun onIceConnectionFailed() {
        connectTimeoutJob?.cancel()
        val safeBinding = _binding ?: return
        if (isCallStarted.get() && currentRoomState != RoomState.CONNECTED) {
            showLobby(safeBinding, "❌ Connection failed.")
        }
    }

    // --- SignalingListener Implementation ---

    override fun onSiteOpened() {
        val safeBinding = _binding ?: return
        if (currentRoomState == RoomState.WAITING) {
            showLobby(safeBinding, "✅ Link opened!")
        }
    }

    override fun onOtpVerified() {
        val safeBinding = _binding ?: return
        if (currentRoomState == RoomState.OTP_SENT) {
            showLobby(safeBinding, "🔐 Access verified!")
            currentRoomState = RoomState.ACTIVE
            activateCallSession()
        }
    }

    override fun onAnswerReceived(sdp: String, type: String) {
        val sessionDescription = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type),
            sdp
        )
        webRtcManager.currentPeerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    synchronized(remoteCandidatesQueue) {
                        remoteCandidatesQueue.forEach {
                            webRtcManager.currentPeerConnection?.addIceCandidate(it)
                        }
                        remoteCandidatesQueue.clear()
                    }
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            },
            sessionDescription
        )
    }

    override fun onCandidateBatchReceived(candidates: List<JsonObject>) {
        candidates.forEach { candidateData ->
            val sdpMid = candidateData["sdpMid"]?.jsonPrimitive?.content
            val sdpMLineIndex =
                candidateData["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val candidateSdp = candidateData["candidate"]?.jsonPrimitive?.content
                ?: candidateData["sdp"]?.jsonPrimitive?.content
            if (candidateSdp != null) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
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

    override fun onWebReady() {
        logger.d("Web is ready, checking if offer needed...")
        val pc = webRtcManager.currentPeerConnection
        if (pc == null) {
            logger.w("onWebReady: PC is null, queuing event")
            pendingWebReady = true
            return
        }

        if (isOfferSent.compareAndSet(false, true)) {
            if (pc.remoteDescription == null &&
                pc.signalingState() == PeerConnection.SignalingState.STABLE
            ) {
                logger.d("Creating Offer...")
                val constraints = MediaConstraints().apply {
                    mandatory.add(
                        MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
                    )
                    val receiveVideo = if (callType.equals("VIDEO", ignoreCase = true)) {
                        "true"
                    } else {
                        "false"
                    }
                    mandatory.add(
                        MediaConstraints.KeyValuePair("OfferToReceiveVideo", receiveVideo)
                    )
                }

                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                signalingClient?.sendOffer(
                                    sdp.description,
                                    sdp.type.canonicalForm()
                                )
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, sdp)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            } else {
                logger.d("onWebReady: PC state not suitable or remote desc already set")
                isOfferSent.set(false) // Reset if we couldn't send
            }
        } else {
            logger.d("onWebReady: Offer already sent or in progress")
        }
    }

    override fun onHangupReceived() {
        teardownAndExit()
    }

    private fun showLobby(safeBinding: FragmentCallRoomBinding, message: String) {
        safeBinding.lobbyContainer.visibility = View.VISIBLE
        safeBinding.videoCallContainer.visibility = View.GONE
        safeBinding.audioCallContainer.visibility = View.GONE
        safeBinding.tvLobbyStatus.text = message
    }

    private fun showCallUi(safeBinding: FragmentCallRoomBinding) {
        safeBinding.lobbyContainer.visibility = View.GONE
        if (callType.equals("VIDEO", ignoreCase = true)) {
            safeBinding.videoCallContainer.visibility = View.VISIBLE
            safeBinding.audioCallContainer.visibility = View.GONE
            safeBinding.tvVideoName.text = inmateName
            webRtcManager.setSpeakerphoneOn(true)
        } else {
            safeBinding.videoCallContainer.visibility = View.GONE
            safeBinding.audioCallContainer.visibility = View.VISIBLE
            safeBinding.tvAudioName.text = inmateName
            safeBinding.tvAudioPhone.text = contactPhone
            webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
        }
    }

    private fun updateRoomStatus(status: String) {
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    DbService.updateFieldsByColumn(
                        "call_rooms",
                        "room_id",
                        id,
                        mapOf("room_status" to status)
                    )
                } catch (ex: Exception) {
                    logger.e("status update failed", ex)
                }
            }
        }
    }

    private fun startRecordingService() {
        val intent = Intent(requireContext(), SecureHardwareRecordingService::class.java).apply {
            putExtra("ROOM_ID", roomId)
            putExtra("KIOSK_ID", kioskId)
            putExtra("SESSION_ID", sessionId)
            putExtra("INMATE_NAME", inmateName)
            putExtra("RECEIVER_NAME", contactPhone)
        }
        requireContext().startForegroundService(intent)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRecordingService() {
        if (isServiceBound) {
            try {
                recordingService?.stopAndUpload()
                requireContext().unbindService(serviceConnection)
            } catch (e: Exception) {
                logger.e("Error stopping recording service", e)
            }
            isServiceBound = false
        }
        try {
            requireContext().stopService(
                Intent(requireContext(), SecureHardwareRecordingService::class.java)
            )
        } catch (e: Exception) {
            logger.e("Error stopping service intent", e)
        }
    }

    private fun stopSmsLoop() {
        smsJob?.cancel()
        smsJob = null
    }

    private fun teardownAndExit() {
        logger.d("teardownAndExit called. Saving balance for Inmate: $inmateId")
        releaseResources()

        val currentBalance = remainingBalanceSeconds
        val uid = inmateId

        // Use separate scope to ensure DB update completes even if Fragment is destroyed.
        // Balance accuracy is critical in a prison communication system.
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                val newBalance = currentBalance.coerceAtLeast(0)
                logger.d("PERSISTENT DB UPDATE: $newBalance seconds for UID: $uid")

                DbService.updateFields(
                    table = "users",
                    id = uid,
                    fields = mapOf("balance_remaining_seconds" to newBalance)
                )
                logger.d("✅ DB PERSIST SUCCESS")
            } catch (e: Exception) {
                logger.e("❌ DB PERSIST FAIL", e)
            }

            withContext(Dispatchers.Main) {
                delay(800)
                deleteRoom()
                showSummaryDialog()
            }
        }
    }

    private fun releaseResources() {
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
            logger.e("Error releasing EGL base", e)
        }
    }

    private fun showSummaryDialog() {
        if (_binding == null) {
            exitToDashboard()
            return
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).create()
        val dialogBinding = DialogCallSummaryBinding.inflate(layoutInflater)
        dialog.setView(dialogBinding.root)
        dialog.setCancelable(false)
        dialogBinding.tvSummaryPhone.text = contactPhone
        dialogBinding.tvSummaryDuration.text = formatDuration(elapsedSeconds)
        dialogBinding.tvSummaryBalance.text =
            formatDuration(remainingBalanceSeconds.coerceAtLeast(0))
        dialogBinding.btnSummaryOk.setOnClickListener {
            dialog.dismiss()
            exitToDashboard()
        }
        dialog.show()
    }

    private fun exitToDashboard() {
        (requireActivity() as? KioskMainActivity)?.navigateToFragment(
            DashboardFragment().apply {
                arguments = Bundle().apply { putString("user_id", inmateId) }
            },
            false
        )
    }

    private fun deleteRoom() {
        roomId?.let { id ->
            lifecycleScope.launch {
                try {
                    DbService.deleteByColumn("call_rooms", "room_id", id)
                } catch (ex: Exception) {
                    logger.e("Room deletion failed", ex)
                }
            }
        }
    }

    override fun onDestroyView() {
        releaseResources()
        super.onDestroyView()
        _binding = null
    }
}