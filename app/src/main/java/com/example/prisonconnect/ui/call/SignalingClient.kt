package com.example.prisonconnect.ui.call

import com.example.prisonconnect.config.SupabaseConfig
import com.example.prisonconnect.util.Logger
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * Client for WebRTC signaling over Supabase Realtime channels.
 *
 * Manages the exchange of signaling messages (offers, answers, ICE candidates,
 * and hangup) between the Android kiosk and the remote inmate browser client.
 *
 * All signaling messages are transmitted via Supabase Realtime broadcasts
 * within a channel named after the room ID.
 *
 * @property roomId The unique room identifier for this call session
 * @property scope The coroutine scope for all signaling operations
 * @property listener Callback for signaling events
 */
class SignalingClient(
    private val roomId: String,
    private val scope: CoroutineScope,
    private val listener: SignalingListener
) {
    private val logger = Logger("SignalingClient")

    /** Internal message structure for the rate-limited queue. */
    private data class OutgoingMessage(val event: String, val payload: JsonObject)

    private val messageQueue = Channel<OutgoingMessage>(capacity = 100)
    private var workerJob: Job? = null

    companion object {
        private const val THROTTLE_DELAY_MS = 100L // 10 messages per second (Max Supabase Broadcast capacity)
    }

    /**
     * Callback interface for signaling events received from the remote peer.
     */
    interface SignalingListener {
        /** The call link has been opened by the remote user. */
        fun onSiteOpened()

        /** The remote user has verified the OTP code. */
        fun onOtpVerified()

        /** The remote browser client is ready for WebRTC negotiation. */
        fun onWebReady()

        /** The remote browser has successfully captured media and attached tracks. */
        fun onBrowserMediaReady()

        /** An SDP answer has been received from the remote peer. */
        fun onAnswerReceived(sdp: String, type: String)

        /** A batch of ICE candidates has been received from the remote peer. */
        fun onCandidateBatchReceived(candidates: List<JsonObject>)

        /** The remote peer has terminated the call. */
        fun onHangupReceived()
    }

    private var realtimeChannel: RealtimeChannel? = null

    /** Tracks if we have already disconnected to prevent double-disconnect. */
    private var isDisconnected = false

    /** Tracks all active listener jobs for proper cleanup. */
    private val listenerJobs = mutableListOf<Job>()

    /** The channel name derived from the room ID. */
    private val channelName: String get() = "room_$roomId"

    /**
     * Connects to the Supabase Realtime channel and registers event listeners.
     *
     * All broadcast listeners are launched in the provided [scope].
     * The channel is subscribed after listeners are registered to avoid
     * missing any events.
     */
    fun connect() {
        startWorker()
        scope.launch {
            try {
                logger.d("Connecting to channel: $channelName")
                realtimeChannel = SupabaseConfig.client.channel(channelName)

                registerEventListeners()

                realtimeChannel?.subscribe()
                logger.d("Successfully subscribed to channel: $channelName")
            } catch (e: Exception) {
                logger.e("Failed to subscribe to channel: $channelName", e)
            }
        }
    }

    /**
     * Registers all broadcast event listeners for signaling.
     *
     * Each listener is launched independently so a failure in one
     * does not affect the others.
     */
    private fun registerEventListeners() {
        val channel = realtimeChannel ?: return

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("site-opened").collect {
                    logger.d("Broadcast received: site-opened")
                    listener.onSiteOpened()
                }
            } catch (e: Exception) {
                logger.e("site-opened listener failed", e)
            }
        })

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("otp-verified").collect {
                    logger.d("Broadcast received: otp-verified")
                    listener.onOtpVerified()
                }
            } catch (e: Exception) {
                logger.e("otp-verified listener failed", e)
            }
        })

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("web-ready").collect {
                    logger.d("Broadcast received: web-ready")
                    listener.onWebReady()
                }
            } catch (e: Exception) {
                logger.e("web-ready listener failed", e)
            }
        })

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("browser-media-ready").collect {
                    logger.d("Broadcast received: browser-media-ready")
                    listener.onBrowserMediaReady()
                }
            } catch (e: Exception) {
                logger.e("browser-media-ready listener failed", e)
            }
        })

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("answer").collect { payload ->
                    logger.d("Broadcast received: answer")
                    val answerData = payload["answer"] as? JsonObject
                    val typeStr = answerData?.get("type")?.jsonPrimitive?.content.orEmpty()
                    val sdpStr = answerData?.get("sdp")?.jsonPrimitive?.content.orEmpty()
                    listener.onAnswerReceived(sdpStr, typeStr)
                }
            } catch (e: Exception) {
                logger.e("answer listener failed", e)
            }
        })

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("candidates-batch").collect { payload ->
                    val candidatesArray = payload["candidates"]?.jsonArray?.mapNotNull {
                        it as? JsonObject
                    } ?: emptyList()
                    logger.d("Broadcast received: candidates-batch (count: ${candidatesArray.size})")
                    listener.onCandidateBatchReceived(candidatesArray)
                }
            } catch (e: Exception) {
                logger.e("candidates-batch listener failed", e)
            }
        })

        listenerJobs.add(scope.launch {
            try {
                channel.broadcastFlow<JsonObject>("hangup").collect {
                    logger.d("Broadcast received: hangup")
                    listener.onHangupReceived()
                }
            } catch (e: Exception) {
                logger.e("hangup listener failed", e)
            }
        })
    }

    /**
     * Starts the background worker that processes the signaling queue at a fixed rate.
     */
    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            for (msg in messageQueue) {
                try {
                    realtimeChannel?.broadcast(event = msg.event, message = msg.payload)
                } catch (e: Exception) {
                    val errorMsg = e.toString()
                    if (errorMsg.contains("RateLimit", ignoreCase = true)) {
                        logger.w("Supabase Rate Limit exceeded! Message delayed: ${msg.event}")
                        // Wait a bit longer if we hit a rate limit
                        delay(500)
                    } else {
                        logger.e("Failed to broadcast signaling event: ${msg.event}", e)
                    }
                }
                delay(THROTTLE_DELAY_MS)
            }
        }
    }

    /**
     * Sends a WebRTC offer SDP to the remote peer.
     *
     * @param sdp The SDP description string
     * @param type The SDP type (e.g., "offer")
     */
    fun sendOffer(sdp: String, type: String) {
        logger.d("Queueing Offer SDP (type: $type)")
        val offerMessage = buildJsonObject {
            put("sender", "android")
            putJsonObject("offer") {
                put("type", type)
                put("sdp", sdp)
            }
        }
        messageQueue.trySend(OutgoingMessage("offer", offerMessage))
    }

    /**
     * Sends a batch of ICE candidates to the remote peer.
     *
     * Candidate batching reduces signaling overhead by grouping
     * multiple candidates into a single broadcast message.
     *
     * @param candidatesJsonArray JSON array of ICE candidate objects
     */
    fun sendCandidateBatch(candidatesJsonArray: JsonArray) {
        logger.d("Queueing ICE candidate batch (size: ${candidatesJsonArray.size})")
        val batchMessage = buildJsonObject {
            put("sender", "android")
            put("candidates", candidatesJsonArray)
        }
        messageQueue.trySend(OutgoingMessage("candidates-batch", batchMessage))
    }

    /**
     * Sends a hangup event and disconnects from the signaling channel.
     *
     * This method is idempotent - calling it multiple times will
     * only result in a single disconnect.
     */
    fun sendHangupAndDisconnect() {
        if (isDisconnected) {
            logger.d("Already disconnected, skipping")
            return
        }
        isDisconnected = true

        scope.launch {
            try {
                logger.d("Sending hangup and disconnecting...")
                val hangupMessage = buildJsonObject { put("sender", "android") }
                realtimeChannel?.broadcast(event = "hangup", message = hangupMessage)
                realtimeChannel?.unsubscribe()
                realtimeChannel = null

                // Cancel all listener and worker jobs
                workerJob?.cancel()
                messageQueue.close()
                listenerJobs.forEach { it.cancel() }
                listenerJobs.clear()

                logger.d("Signaling disconnected successfully")
            } catch (e: Exception) {
                logger.e("Error during signaling disconnect", e)
            }
        }
    }
}