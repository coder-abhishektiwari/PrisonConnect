package com.example.prisonconnect.ui.call

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.example.prisonconnect.R
import com.example.prisonconnect.databinding.ActivityVideoCallBinding
import com.example.prisonconnect.ui.common.BaseCallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*

/**
 * Activity for Video calls.
 */
class VideoCallActivity : BaseCallActivity<ActivityVideoCallBinding>() {

    override val tag = "VideoCallActivity"
    override val isVideoMode = true
    private var isMicEnabled = true
    private var isSpeakerEnabled = true

    override fun inflateBinding() = ActivityVideoCallBinding.inflate(layoutInflater)

    override fun setupCallUi() {
        binding.tvVideoName.text = contactName

        binding.btnVideoHangup.setOnClickListener { confirmExit() }
        binding.btnCancelCall.setOnClickListener{ confirmExit() }
        binding.btnVideoMic.setOnClickListener { toggleMic() }
        binding.btnVideoSpeaker.setOnClickListener { toggleSpeaker() }
        binding.btnVideoSwitch.setOnClickListener { webRtcManager.switchCamera() }

        setupOverlayLogic()
        setupVideoDrag()
    }

    override fun onCallActivated() {
        if (!isCallStarted.compareAndSet(false, true)) return
        log("Activating WebRTC Video...")

        lifecycleScope.launch(Dispatchers.Main) {
            waitForWebRtcReady()
            
            try {
                binding.localView.init(eglBase.eglBaseContext, null)
                binding.localView.setMirror(true)
                binding.localView.setEnableHardwareScaler(true)
                binding.localView.setZOrderMediaOverlay(true)
                binding.localView.setZOrderOnTop(true)
                binding.localView.visibility = View.VISIBLE

                binding.remoteView.init(eglBase.eglBaseContext, null)
                binding.remoteView.setEnableHardwareScaler(true)
                binding.remoteView.setMirror(false)

                webRtcManager.setupPeerConnection(isVideo = true)
                webRtcManager.startLocalStream(binding.localView, isVideo = true)

                showCallUi()
            } catch (e: Exception) {
                log("Failed to activate WebRTC", e)
                isCallStarted.set(false)
            }
        }
    }

    override fun onTimerUpdated(seconds: Long) {
        binding.tvVideoDuration.text = formatSeconds(seconds)
    }

    override fun onBalanceUpdated(balance: Long) {
        binding.tvVideoBalance.text = "Bal: ${formatSeconds(balance)}"
        super.onBalanceUpdated(balance)
    }

    override fun updateLobbyStatus(message: String) {
        binding.tvLobbyStatus.text = message
    }

    override fun showCallUi() {
        runOnUiThread {
            if (binding.lobbyContainer.visibility != View.GONE) {
                binding.lobbyContainer.visibility = View.GONE
                binding.videoCallUi.visibility = View.VISIBLE
                webRtcManager.setSpeakerphoneOn(true)
                viewModel.startCallTimer()
            }
        }
    }

    private fun toggleMic() {
        isMicEnabled = !isMicEnabled
        updateMicUI()
        webRtcManager.setAudioEnabled(isMicEnabled)
    }

    private fun updateMicUI() {
        binding.btnVideoMic.isActivated = isMicEnabled
        binding.btnVideoMic.alpha = if (isMicEnabled) 1f else 0.6f

        if (isMicEnabled) {
            binding.btnVideoMic.setIconResource(R.drawable.ic_mic)
            binding.btnVideoMic.setIconTintResource(R.color.white)
        } else {
            binding.btnVideoMic.setIconResource(R.drawable.ic_mic_off)
            binding.btnVideoMic.setIconTintResource(R.color.danger)
        }
    }

    private fun toggleSpeaker() {
        isSpeakerEnabled = !isSpeakerEnabled
        updateSpeakerUI()
        // Mute/Unmute the remote audio output instead of switching devices
        webRtcManager.setRemoteAudioEnabled(isSpeakerEnabled)
    }

    private fun updateSpeakerUI() {
        binding.btnVideoSpeaker.isActivated = isSpeakerEnabled
        binding.btnVideoSpeaker.alpha = if (isSpeakerEnabled) 1f else 0.6f

        if (isSpeakerEnabled) {
            binding.btnVideoSpeaker.setIconResource(R.drawable.ic_speaker)
            binding.btnVideoSpeaker.setIconTintResource(R.color.white)
        } else {
            binding.btnVideoSpeaker.setIconResource(R.drawable.ic_speaker_off)
            binding.btnVideoSpeaker.setIconTintResource(R.color.danger)
        }
    }

    override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
        log("Remote video track received, attaching to sink...")
        runOnUiThread {
            try {
                videoTrack.setEnabled(true)
                videoTrack.addSink(binding.remoteView)
                recordingService?.let { videoTrack.addSink(it) }
                binding.remoteView.requestLayout()
            } catch (e: Exception) {
                log("Error attaching remote video sink", e)
            }
        }
    }

    override fun releaseResources() {
        super.releaseResources()
        try {
            binding.localView.release()
            binding.remoteView.release()
        } catch (e: Exception) {
            log("Error releasing renderers", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVideoDrag() {
        var dX = 0f
        var dY = 0f
        binding.localView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start()
                }
            }
            true
        }
    }

    private fun setupOverlayLogic() {
        binding.videoClickOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) toggleOverlays()
            true
        }
    }

    private fun toggleOverlays() {
        val visible = binding.videoHeader.visibility == View.VISIBLE
        binding.videoHeader.visibility = if (visible) View.GONE else View.VISIBLE
        binding.videoFooter.visibility = if (visible) View.GONE else View.VISIBLE
    }
}
