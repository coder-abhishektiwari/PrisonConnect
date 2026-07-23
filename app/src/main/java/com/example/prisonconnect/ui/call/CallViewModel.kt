package com.example.prisonconnect.ui.call

import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.webrtc.IceCandidate

/**
 * ViewModel for managing the signaling flow and WebRTC state for a call.
 *
 * Extends [BaseCallViewModel] with:
 * - Signaling client lifecycle
 * - ICE candidate batching and sending
 * - Signal event emission for the Activity/Fragment to observe
 *
 * The Activity/Fragment observes [signalingEvents] to react to signaling
 * events such as offer received, answer received, etc.
 */
class CallViewModel : BaseCallViewModel(), SignalingClient.SignalingListener {

    override val TAG = "CallViewModel"
    override val logger = Logger(TAG)

    private var signalingClient: SignalingClient? = null

    /** Signaling events emitted for the UI layer to observe. */
    private val _signalingEvents = MutableSharedFlow<SignalingEvent>(replay = 1)
    val signalingEvents: SharedFlow<SignalingEvent> = _signalingEvents.asSharedFlow()

    /**
     * Events emitted by the signaling client for the UI to process.
     */
    sealed class SignalingEvent {
        /** The call link has been opened by the remote user on their browser. */
        object SiteOpened : SignalingEvent()

        /** The remote user has verified the OTP code. */
        object OtpVerified : SignalingEvent()

        /** The remote browser is ready for WebRTC negotiation. */
        object WebReady : SignalingEvent()

        /** The remote browser has local media tracks attached and is ready to negotiate. */
        object BrowserMediaReady : SignalingEvent()

        /** An SDP answer has been received from the remote peer. */
        data class AnswerReceived(val sdp: String, val type: String) : SignalingEvent()

        /** A batch of ICE candidates has been received from the remote peer. */
        data class CandidatesReceived(val candidates: List<JsonObject>) : SignalingEvent()

        /** The remote peer has terminated the call. */
        object HangupReceived : SignalingEvent()
    }

    /**
     * Initializes the signaling client and connects to the Supabase channel.
     *
     * @param roomId The call room ID used for the Supabase Realtime channel
     */
    fun startSignaling(roomId: String) {
        signalingClient = SignalingClient(roomId, viewModelScope, this)
        signalingClient?.connect()
        logger.d("Signaling started for room: $roomId")
    }

    // --- SignalingClient.SignalingListener Implementation ---

    override fun onSiteOpened() {
        logger.d("Signaling: site-opened")
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.SiteOpened) }
    }

    override fun onOtpVerified() {
        logger.d("Signaling: otp-verified")
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.OtpVerified) }
    }

    override fun onWebReady() {
        logger.d("Signaling: web-ready")
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.WebReady) }
    }

    override fun onBrowserMediaReady() {
        logger.d("Signaling: browser-media-ready")
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.BrowserMediaReady) }
    }

    override fun onAnswerReceived(sdp: String, type: String) {
        logger.d("Signaling: answer received")
        viewModelScope.launch {
            _signalingEvents.emit(SignalingEvent.AnswerReceived(sdp, type))
        }
    }

    override fun onCandidateBatchReceived(candidates: List<JsonObject>) {
        logger.d("Signaling: candidates-batch received (count: ${candidates.size})")
        viewModelScope.launch {
            _signalingEvents.emit(SignalingEvent.CandidatesReceived(candidates))
        }
    }

    override fun onHangupReceived() {
        logger.d("Signaling: hangup received")
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.HangupReceived) }
    }

    // --- Actions called by Activity/Fragment ---

    /**
     * Sends the local SDP offer to the remote peer via the signaling client.
     *
     * @param sdp The SDP description string
     * @param type The SDP type (e.g., "offer")
     */
    fun sendOffer(sdp: String, type: String) {
        signalingClient?.sendOffer(sdp, type)
    }

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var candidateBatchJob: Job? = null

    /**
     * Queues a local ICE candidate to be sent in a batch.
     * Batching reduces message frequency to stay within signaling rate limits.
     *
     * @param candidate The ICE candidate to send
     */
    fun queueLocalCandidate(candidate: IceCandidate) {
        synchronized(pendingCandidates) {
            pendingCandidates.add(candidate)
        }

        if (candidateBatchJob == null || candidateBatchJob?.isActive == false) {
            candidateBatchJob = viewModelScope.launch {
                delay(100) // 100ms batching window
                sendCandidateBatch()
            }
        }
    }

    private fun sendCandidateBatch() {
        val candidatesToSend = synchronized(pendingCandidates) {
            val list = pendingCandidates.toList()
            pendingCandidates.clear()
            list
        }

        if (candidatesToSend.isEmpty()) return

        val jsonArray = JsonArray(candidatesToSend.map { candidate ->
            buildJsonObject {
                put("sdpMid", candidate.sdpMid ?: "")
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
        })
        
        signalingClient?.sendCandidateBatch(jsonArray)
    }

    /**
     * Ends the call by updating status, sending hangup signal, and persisting balance.
     */
    fun endCall() {
        updateRoomStatus("DISCONNECTED")
        signalingClient?.sendHangupAndDisconnect()
        persistFinalBalance()
    }

    override fun onCleared() {
        signalingClient?.sendHangupAndDisconnect()
        super.onCleared()
    }
}