package com.example.prisonconnect.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.webrtc.*

class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: WebRtcListener
) {
    interface WebRtcListener {
        fun onIceCandidateGenerated(candidate: IceCandidate)
        fun onIceConnected()
        fun onIceConnectionFailed()
        fun onRemoteVideoTrackReceived(videoTrack: VideoTrack)
    }

    private var factory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null
        private set
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    suspend fun initialize() = withContext(Dispatchers.Default) {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
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
            Log.d("WebRtcManager", "PeerConnectionFactory initialized successfully")
        } catch (e: Exception) {
            Log.e("WebRtcManager", "Failed to initialize PeerConnectionFactory", e)
        }
    }

    fun setupPeerConnection() {
        val factory = factory ?: run {
            Log.e("WebRtcManager", "setupPeerConnection: Factory is null")
            return
        }

        val iceServers = mutableListOf<PeerConnection.IceServer>()
        
        // 1. Add Google STUN servers (Primary - Free)
        val googleStuns = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302"
        )
        googleStuns.forEach { url ->
            iceServers.add(PeerConnection.IceServer.builder(url).createIceServer())
        }
        
        // 2. Add Metered.ca TURN server (Fallback - Paid/Restricted)
        val meteredUser = com.example.prisonconnect.config.SupabaseConfig.METERED_USERNAME
        val meteredPass = com.example.prisonconnect.config.SupabaseConfig.METERED_PASSWORD

        if (meteredUser.isNotBlank() && meteredPass.isNotBlank()) {
            val meteredUrls = listOf(
                "stun:stun.relay.metered.ca:80",
                "turn:global.relay.metered.ca:80",
                "turn:global.relay.metered.ca:80?transport=tcp",
                "turn:global.relay.metered.ca:443",
                "turns:global.relay.metered.ca:443?transport=tcp"
            )
            
            meteredUrls.forEach { url ->
                val builder = PeerConnection.IceServer.builder(url)
                if (url.startsWith("turn")) {
                    builder.setUsername(meteredUser)
                    builder.setPassword(meteredPass)
                }
                iceServers.add(builder.createIceServer())
            }
            Log.d("WebRtcManager", "Metered.ca TURN fallback configured with ${meteredUrls.size} endpoints")
        } else {
            Log.w("WebRtcManager", "Metered.ca credentials missing, using Google STUN only.")
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Optimize for mobile/cellular
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Use all types (STUN + TURN)
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidateGenerated(candidate)
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                // Handle remote tracks
                streams.forEach { stream ->
                    stream.videoTracks.forEach { track ->
                        Log.d("WebRtcManager", "Remote video track added: ${track.id()}")
                        listener.onRemoteVideoTrackReceived(track)
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d("WebRtcManager", "ICE connection state changed: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        listener.onIceConnected()
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        listener.onIceConnectionFailed()
                    }
                    else -> {
                        // Other states: NEW, CHECKING, CLOSED - no action needed
                    }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(p0: MediaStream?) {}
        })
    }

    fun startLocalStream(localSink: VideoSink) {
        val factory = factory ?: return
        
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource).apply { this?.setEnabled(true) }

        videoCapturer = createVideoCapturer()
        val videoSource = factory.createVideoSource(false)

        videoCapturer?.let { capturer ->
            try {
                capturer.initialize(
                    SurfaceTextureHelper.create("VideoCapturerThread", eglBase.eglBaseContext),
                    context,
                    videoSource?.capturerObserver
                )
                capturer.startCapture(1280, 720, 30)
                Log.d("WebRtcManager", "Camera capture started successfully")
            } catch (e: Exception) {
                Log.e("WebRtcManager", "Failed to start camera capture", e)
            }
        }

        localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource).apply {
            this?.setEnabled(true)
            this?.addSink(localSink)
        }

        peerConnection?.addTrack(localAudioTrack)
        peerConnection?.addTrack(localVideoTrack)
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        return enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
    }

    fun cleanup() {
        Log.d("WebRtcManager", "Starting WebRTC cleanup...")
        try {
            videoCapturer?.let { capturer ->
                capturer.stopCapture()
                capturer.dispose()
            }
        } catch (e: Exception) {
            Log.e("WebRtcManager", "Error stopping/disposing videoCapturer", e)
        }
        
        try { localVideoTrack?.dispose() } catch (e: Exception) {}
        try { localAudioTrack?.dispose() } catch (e: Exception) {}
        try { peerConnection?.dispose() } catch (e: Exception) {}
        try { factory?.dispose() } catch (e: Exception) {}
        
        videoCapturer = null
        localVideoTrack = null
        localAudioTrack = null
        peerConnection = null
        factory = null
        Log.d("WebRtcManager", "WebRTC cleanup finished.")
    }
}