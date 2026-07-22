package com.example.prisonconnect.webrtc

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

    // ICE Candidate Batching
    private val localCandidatesQueue = mutableListOf<IceCandidate>()
    private var candidateBatchJob: Job? = null

    companion object {
        /** Delay in milliseconds before sending a batch of ICE candidates. */
        private const val CANDIDATE_BATCH_DELAY_MS = 300L
    }

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

    /**
     * Queues a local ICE candidate for batched transmission.
     *
     * Candidates are accumulated and sent as a batch after [CANDIDATE_BATCH_DELAY_MS]
     * milliseconds of inactivity, reducing signaling overhead.
     *
     * @param candidate The ICE candidate to send
     */
    fun queueLocalCandidate(candidate: IceCandidate) {
        synchronized(localCandidatesQueue) {
            localCandidatesQueue.add(candidate)
        }

        if (candidateBatchJob?.isActive != true) {
            candidateBatchJob = viewModelScope.launch {
                delay(CANDIDATE_BATCH_DELAY_MS)
                val batch = synchronized(localCandidatesQueue) {
                    val list = localCandidatesQueue.toList()
                    localCandidatesQueue.clear()
                    list
                }
                if (batch.isNotEmpty()) {
                    val jsonArray = JsonArray(batch.map {
                        buildJsonObject {
                            put("sdpMid", it.sdpMid ?: "")
                            put("sdpMLineIndex", it.sdpMLineIndex)
                            put("candidate", it.sdp)
                        }
                    })
                    signalingClient?.sendCandidateBatch(jsonArray)
                    logger.d("Sent ICE candidate batch: ${batch.size} candidates")
                }
            }
        }
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