package com.example.prisonconnect.webrtc

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * ViewModel for managing the signaling flow and WebRTC state for a call.
 */
class CallViewModel : BaseCallViewModel(), SignalingClient.SignalingListener {

    override val TAG = "CallViewModel"

    private var signalingClient: SignalingClient? = null
    
    // Signaling Events for Activity to observe
    private val _signalingEvents = MutableSharedFlow<SignalingEvent>()
    val signalingEvents: SharedFlow<SignalingEvent> = _signalingEvents.asSharedFlow()

    // ICE Candidate Batching
    private val localCandidatesQueue = mutableListOf<IceCandidate>()
    private var candidateBatchJob: Job? = null

    sealed class SignalingEvent {
        object SiteOpened : SignalingEvent()
        object OtpVerified : SignalingEvent()
        object WebReady : SignalingEvent()
        data class AnswerReceived(val sdp: String, val type: String) : SignalingEvent()
        data class CandidatesReceived(val candidates: List<JsonObject>) : SignalingEvent()
        object HangupReceived : SignalingEvent()
    }

    fun startSignaling(roomId: String) {
        signalingClient = SignalingClient(roomId, viewModelScope, this)
        signalingClient?.connect()
    }

    // --- SignalingClient.SignalingListener Implementation ---

    override fun onSiteOpened() {
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.SiteOpened) }
    }

    override fun onOtpVerified() {
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.OtpVerified) }
    }

    override fun onWebReady() {
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.WebReady) }
    }

    override fun onAnswerReceived(sdp: String, type: String) {
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.AnswerReceived(sdp, type)) }
    }

    override fun onCandidateBatchReceived(candidates: List<JsonObject>) {
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.CandidatesReceived(candidates)) }
    }

    override fun onHangupReceived() {
        viewModelScope.launch { _signalingEvents.emit(SignalingEvent.HangupReceived) }
    }

    // --- Actions called by Activity ---

    fun sendOffer(sdp: String, type: String) {
        signalingClient?.sendOffer(sdp, type)
    }

    fun queueLocalCandidate(candidate: IceCandidate) {
        synchronized(localCandidatesQueue) {
            localCandidatesQueue.add(candidate)
        }
        
        if (candidateBatchJob?.isActive != true) {
            candidateBatchJob = viewModelScope.launch {
                delay(300)
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
                }
            }
        }
    }

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