package com.example.prisonconnect.ui.call

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.example.prisonconnect.R
import com.example.prisonconnect.databinding.ActivityAudioCallBinding
import com.example.prisonconnect.ui.common.BaseCallActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*

/**
 * Activity for Audio calls.
 */
class AudioCallActivity : BaseCallActivity<ActivityAudioCallBinding>() {

    override val tag = "AudioCallActivity"
    override val isVideoMode = false
    private var isMicEnabled = true
    private var isSpeakerEnabled = false

    override fun inflateBinding() = ActivityAudioCallBinding.inflate(layoutInflater)

    override fun setupCallUi() {
        binding.tvAudioName.text = contactName
        binding.tvAudioPhone.text = contactPhone

        binding.btnAudioHangup.setOnClickListener { confirmExit() }
        binding.btnCancelCall.setOnClickListener { confirmExit() }
        binding.btnAudioMic.setOnClickListener { toggleMic() }
        binding.btnAudioSpeaker.setOnClickListener { toggleSpeaker() }
        binding.btnAudioInfo.setOnClickListener { showCallInfoTooltip() }
    }

    override fun onCallActivated() {
        if (!isCallStarted.compareAndSet(false, true)) return
        log("Activating WebRTC Audio...")

        lifecycleScope.launch(Dispatchers.Main) {
            waitForWebRtcReady()

            try {
                webRtcManager.setupPeerConnection(isVideo = false)
                webRtcManager.startLocalStream(null, isVideo = false)

                showCallUi()
            } catch (e: Exception) {
                log("Failed to activate WebRTC audio", e)
                isCallStarted.set(false)
            }
        }
    }

    override fun onTimerUpdated(seconds: Long) {
        binding.tvAudioDuration.text = formatSeconds(seconds)
    }

    override fun updateLobbyStatus(message: String) {
        runOnUiThread {
            binding.tvLobbyStatus.text = message
        }
    }

    override fun showCallUi() {
        runOnUiThread {
            if (binding.lobbyContainer.visibility != View.GONE) {
                binding.lobbyContainer.visibility = View.GONE
                binding.audioCallUi.visibility = View.VISIBLE
                webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
                viewModel.startCallTimer()
            }
        }
    }


//    private fun initAudioControls() {
//        isMicEnabled = true
//        isSpeakerEnabled = true // ya false
//
//        webRtcManager.setAudioEnabled(isMicEnabled)
//        webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
//
//        // Mic UI
//        binding.btnAudioMic.isActivated = isMicEnabled
//        binding.btnAudioMic.alpha = if (isMicEnabled) 1f else 0.6f
//        binding.btnAudioMic.setIconResource(
//            if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
//        )
//
//        // Speaker UI
//        binding.btnAudioSpeaker.isActivated = isSpeakerEnabled
//        binding.btnAudioSpeaker.alpha = if (isSpeakerEnabled) 1f else 0.6f
//    }

    private fun toggleMic() {
        updateMicUI()
        isMicEnabled = !isMicEnabled
        webRtcManager.setAudioEnabled(isMicEnabled)
    }

    private fun updateMicUI() {
        binding.btnAudioMic.isActivated = isMicEnabled
        binding.btnAudioMic.alpha = if (isMicEnabled) 1f else 0.6f

        if (isMicEnabled) {
            binding.btnAudioMic.setIconResource(R.drawable.ic_mic)
            binding.btnAudioMic.setIconTintResource(R.color.white)
        } else {
            binding.btnAudioMic.setIconResource(R.drawable.ic_mic_off)
            binding.btnAudioMic.setIconTintResource(R.color.danger)
        }
    }

    private fun toggleSpeaker() {
        isSpeakerEnabled = !isSpeakerEnabled

        binding.btnAudioSpeaker.isActivated = isSpeakerEnabled
        binding.btnAudioSpeaker.alpha = if (isSpeakerEnabled) 1f else 0.6f

        if (isSpeakerEnabled) {
            binding.btnAudioSpeaker.setIconTintResource(R.color.primary)
        } else {
            binding.btnAudioSpeaker.setIconTintResource(R.color.white)
        }
        webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
    }

    private fun showCallInfoTooltip() {
        val info = "Inmate: $inmateName\nNumber: $contactPhone\nFacility: $jailName"
        MaterialAlertDialogBuilder(this)
            .setTitle("Call Details")
            .setMessage(info)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
        log("Remote video track received but ignored in audio mode")
    }
}
