package com.example.prisonconnect.webrtc

import android.content.Context
import android.media.AudioManager
import com.example.prisonconnect.config.SupabaseConfig
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.webrtc.*

@Suppress("DEPRECATION")
class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: WebRtcListener
) {
    private val logger = Logger("WebRtcManager")

    interface WebRtcListener {
        fun onIceCandidateGenerated(candidate: IceCandidate)
        fun onIceConnected()
        fun onIceConnectionFailed()
        fun onRemoteVideoTrackReceived(videoTrack: VideoTrack)
    }

    private var factory: PeerConnectionFactory? = null
    private var internalPeerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localStream: MediaStream? = null

    val currentPeerConnection: PeerConnection?
        get() = internalPeerConnection

    val hasLocalVideoTrack: Boolean
        get() = localVideoTrack != null

    val hasLocalAudioTrack: Boolean
        get() = localAudioTrack != null

    val isTracksReady: Boolean
        get() = localVideoTrack != null || localAudioTrack != null

    companion object {
        private const val ICE_CONNECTION_RECEIVING_TIMEOUT_MS = 3000
        private const val VIDEO_CAPTURE_WIDTH = 1280
        private const val VIDEO_CAPTURE_HEIGHT = 720
        private const val VIDEO_CAPTURE_FPS = 30
        private const val LOCAL_AUDIO_TRACK_ID = "ARDAMSa0"
        private const val LOCAL_VIDEO_TRACK_ID = "ARDAMSv0"
    }

    suspend fun initialize() = withContext(Dispatchers.Default) {
        try {
            logger.d("Initializing PeerConnectionFactory...")
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
            logger.d("PeerConnectionFactory initialized successfully")
        } catch (e: Exception) {
            logger.e("Failed to initialize PeerConnectionFactory", e)
        }
    }

    fun setupPeerConnection(isVideo: Boolean) {
        val currentFactory = factory ?: run {
            logger.e("setupPeerConnection: Factory is null")
            return
        }

        val iceServers = buildIceServers()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            iceConnectionReceivingTimeout = ICE_CONNECTION_RECEIVING_TIMEOUT_MS
        }

        internalPeerConnection = currentFactory.createPeerConnection(
            rtcConfig,
            createPeerConnectionObserver(isVideo)
        )

        // Stream container initialization
        localStream = currentFactory.createLocalMediaStream("ARDAMS")
        logger.d("Local media stream created for isVideo=$isVideo")
    }

    private fun createPeerConnectionObserver(isVideo: Boolean): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                logger.d("New local ICE candidate: ${candidate.sdpMid}")
                listener.onIceCandidateGenerated(candidate)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                logger.d("ICE connection state changed: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        listener.onIceConnected()
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        listener.onIceConnectionFailed()
                    }
                    else -> { }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                logger.d("Signaling state changed: $state")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                logger.d("ICE gathering state changed: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                logger.d("ICE connection receiving: $receiving")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                candidates?.forEach { candidate ->
                    logger.d("ICE candidate removed: ${candidate.sdpMid}")
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                logger.d("Track added: ${receiver.id()}")
                val track = receiver.track()
                if (track is VideoTrack) {
                    listener.onRemoteVideoTrackReceived(track)
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                // Deprecated but required for compilation
                logger.d("Stream added (deprecated): ${stream?.id}")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                // Deprecated but required for compilation
                logger.d("Stream removed (deprecated): ${stream?.id}")
            }

            override fun onDataChannel(channel: DataChannel?) {
                logger.d("Data channel created: ${channel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                logger.d("Renegotiation needed")
            }
        }
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()

        listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302"
        ).forEach { url ->
            iceServers.add(PeerConnection.IceServer.builder(url).createIceServer())
        }

        val meteredUser = SupabaseConfig.METERED_USERNAME
        val meteredPass = SupabaseConfig.METERED_PASSWORD

        if (meteredUser.isNotBlank() && meteredPass.isNotBlank()) {
            listOf(
                "stun:stun.relay.metered.ca:80",
                "turn:global.relay.metered.ca:80",
                "turn:global.relay.metered.ca:80?transport=tcp",
                "turn:global.relay.metered.ca:443",
                "turns:global.relay.metered.ca:443?transport=tcp"
            ).forEach { url ->
                val builder = PeerConnection.IceServer.builder(url)
                if (url.startsWith("turn") || url.startsWith("turns")) {
                    builder.setUsername(meteredUser)
                    builder.setPassword(meteredPass)
                }
                iceServers.add(builder.createIceServer())
            }
        }

        logger.d("Configured ${iceServers.size} ICE servers")
        return iceServers
    }

    fun startLocalStream(localSink: VideoSink?, isVideo: Boolean) {
        val currentFactory = factory ?: return

        setupLocalAudio(currentFactory)

        if (isVideo && localSink != null) {
            setupLocalVideo(currentFactory, localSink)
        }
    }

    private fun setupLocalAudio(factory: PeerConnectionFactory) {
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
        }
        localStream?.addTrack(localAudioTrack)

        // Add audio track to Peer Connection
        localAudioTrack?.let { track ->
            localStream?.let { stream ->
                internalPeerConnection?.addTrack(track, listOf(stream.id))
            }
        }
        logger.d("Local audio track initialized and added to peer connection")
    }

    private fun setupLocalVideo(factory: PeerConnectionFactory, localSink: VideoSink) {
        videoCapturer = createVideoCapturer()
        val videoSource = factory.createVideoSource(false)

        videoCapturer?.let { capturer ->
            try {
                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "VideoCapturerThread",
                    eglBase.eglBaseContext
                )
                capturer.initialize(
                    surfaceTextureHelper,
                    context,
                    videoSource.capturerObserver
                )
                // Start capture BEFORE creating and attaching video track
                capturer.startCapture(VIDEO_CAPTURE_WIDTH, VIDEO_CAPTURE_HEIGHT, VIDEO_CAPTURE_FPS)
                logger.d("Camera capture started successfully")
            } catch (e: Exception) {
                logger.e("Failed to start camera capture", e)
            }
        }

        // Create local track
        localVideoTrack = factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, videoSource).apply {
            setEnabled(true)
        }

        // IMPORTANT FIX: Explicitly add sink AND force track enabled
        localVideoTrack?.addSink(localSink)

        localStream?.addTrack(localVideoTrack)

        // Unified Plan: add track to peer connection after creating it
        localVideoTrack?.let { track ->
            localStream?.let { stream ->
                internalPeerConnection?.addTrack(track, listOf(stream.id))
            }
        }
        logger.d("Local video track added to stream and peer connection")
    }


    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    logger.d("Using front-facing camera: $deviceName")
                    return capturer
                }
            }
        }

        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    logger.d("Using back-facing camera: $deviceName")
                    return capturer
                }
            }
        }

        logger.w("No suitable camera found")
        return null
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        logger.d("Camera switched")
    }

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        logger.d("Audio enabled: $enabled")
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphoneOn(on: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
        logger.d("Speakerphone on: $on")
    }

    fun cleanup() {
        logger.d("Starting WebRTC cleanup...")

        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            logger.e("Error stopping video capturer", e)
        }
        videoCapturer = null

        surfaceTextureHelper = null

        localVideoTrack = null
        localAudioTrack = null

        internalPeerConnection = null

        localStream = null
        factory = null

        logger.d("WebRTC cleanup finished.")
    }
}