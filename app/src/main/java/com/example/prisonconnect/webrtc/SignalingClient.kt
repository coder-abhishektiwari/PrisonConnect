package com.example.prisonconnect.webrtc

import android.util.Log
import com.example.prisonconnect.config.SupabaseConfig
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class SignalingClient(
    private val roomId: String,
    private val scope: CoroutineScope,
    private val listener: SignalingListener
) {
    interface SignalingListener {
        fun onSiteOpened()
        fun onOtpVerified()
        fun onWebReady()
        fun onAnswerReceived(sdp: String, type: String)
        fun onCandidateBatchReceived(candidates: List<JsonObject>)
        fun onHangupReceived()
    }

    private var realtimeChannel: RealtimeChannel? = null

    fun connect() {
        scope.launch {
            try {
                realtimeChannel = SupabaseConfig.client.channel("room_$roomId")

                scope.launch {
                    realtimeChannel?.broadcastFlow<JsonObject>("site-opened")?.collect { 
                        Log.d("SignalingClient", "Event received: site-opened")
                        listener.onSiteOpened() 
                    }
                }
                scope.launch {
                    realtimeChannel?.broadcastFlow<JsonObject>("otp-verified")?.collect { 
                        Log.d("SignalingClient", "Event received: otp-verified")
                        listener.onOtpVerified() 
                    }
                }
                scope.launch {
                    realtimeChannel?.broadcastFlow<JsonObject>("web-ready")?.collect { 
                        Log.d("SignalingClient", "Event received: web-ready")
                        listener.onWebReady() 
                    }
                }
                scope.launch {
                    realtimeChannel?.broadcastFlow<JsonObject>("answer")?.collect { payload ->
                        Log.d("SignalingClient", "Event received: answer")
                        val answerData = payload["answer"] as? JsonObject
                        val typeStr = answerData?.get("type")?.jsonPrimitive?.content ?: ""
                        val sdpStr = answerData?.get("sdp")?.jsonPrimitive?.content ?: ""
                        Log.d("SignalingClient", "Answer SDP: ${sdpStr.take(50)}...")
                        listener.onAnswerReceived(sdpStr, typeStr)
                    }
                }
                scope.launch {
                    realtimeChannel?.broadcastFlow<JsonObject>("candidates-batch")?.collect { payload ->
                        val candidatesArray = payload["candidates"]?.jsonArray?.mapNotNull { 
                            it as? JsonObject 
                        } ?: emptyList()
                        Log.d("SignalingClient", "Event received: candidates-batch (count: ${candidatesArray.size})")
                        // Ensure we map 'candidate' key from payload to whatever internal logic expects
                        listener.onCandidateBatchReceived(candidatesArray)
                    }
                }
                scope.launch {
                    realtimeChannel?.broadcastFlow<JsonObject>("hangup")?.collect { 
                        Log.d("SignalingClient", "Event received: hangup")
                        listener.onHangupReceived() 
                    }
                }

                realtimeChannel?.subscribe()
                Log.d("SignalingClient", "Subscribed to channel: room_$roomId")
            } catch (e: Exception) {
                Log.e("SignalingClient", "Failed to subscribe", e)
            }
        }
    }

    fun sendOffer(sdp: String, type: String) {
        scope.launch {
            Log.d("SignalingClient", "Sending Offer SDP: ${sdp.take(50)}...")
            val offerMessage = buildJsonObject {
                put("sender", "android")
                putJsonObject("offer") {
                    put("type", type)
                    put("sdp", sdp)
                }
            }
            realtimeChannel?.broadcast(event = "offer", message = offerMessage)
        }
    }

    fun sendCandidateBatch(candidatesJsonArray: JsonArray) {
        scope.launch {
            Log.d("SignalingClient", "Sending ICE candidate batch (size: ${candidatesJsonArray.size})")
            val batchMessage = buildJsonObject {
                put("sender", "android")
                put("candidates", candidatesJsonArray)
            }
            realtimeChannel?.broadcast(event = "candidates-batch", message = batchMessage)
        }
    }

    fun sendHangupAndDisconnect() {
        scope.launch {
            try {
                val hangupMessage = buildJsonObject { put("sender", "android") }
                realtimeChannel?.broadcast(event = "hangup", message = hangupMessage)
                realtimeChannel?.unsubscribe()
            } catch (e: Exception) {
                Log.e("SignalingClient", "Error in disconnect", e)
            }
        }
    }
}