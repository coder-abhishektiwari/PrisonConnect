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
        binding.btnCancelCall.setOnClickListener { cancelLobbyCall() }
        binding.btnAudioMic.setOnClickListener { toggleMic() }
        binding.btnAudioSpeaker.setOnClickListener { toggleSpeaker() }
        binding.btnAudioInfo.setOnClickListener { showCallInfoTooltip() }
    }

    override fun onCallActivated() {
        if (!isCallStarted.compareAndSet(false, true)) return
        log("Activating WebRTC Audio...")

        // Promote service to foreground now that the call is starting
        promoteRecordingServiceToForeground()

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

    override fun updateLobbyStatus(message: String, type: BaseCallActivity.LobbyStatusType) {
        runOnUiThread {
            binding.tvLobbyStatus.text = message
            val colorRes = when (type) {
                BaseCallActivity.LobbyStatusType.SUCCESS -> R.color.primary
                BaseCallActivity.LobbyStatusType.FAILURE -> R.color.danger
                BaseCallActivity.LobbyStatusType.PENDING -> R.color.sms
            }
            binding.tvLobbyStatus.setTextColor(getColor(colorRes))
        }
    }

    override fun showCallUi() {
        runOnUiThread {
            if (binding.lobbyContainer.visibility != View.GONE) {
                binding.lobbyContainer.visibility = View.GONE
                binding.audioCallUi.visibility = View.VISIBLE
                webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
                updateSpeakerUI()
                updateMicUI()
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
        isMicEnabled = !isMicEnabled
        webRtcManager.setAudioEnabled(isMicEnabled)
        updateMicUI()
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
        webRtcManager.setSpeakerphoneOn(isSpeakerEnabled)
        updateSpeakerUI()
    }

    private fun updateSpeakerUI() {
        binding.btnAudioSpeaker.isActivated = isSpeakerEnabled
        binding.btnAudioSpeaker.alpha = if (isSpeakerEnabled) 1f else 0.6f

        binding.btnAudioSpeaker.setIconTintResource(
            if (isSpeakerEnabled) R.color.primary else R.color.white
        )
    }

    private fun showCallInfoTooltip() {
        val duration = formatSeconds(viewModel.elapsedSeconds.value)
        val balance = formatDuration(viewModel.remainingBalance.value)

        val info = "Name: $contactName\n" +
                "Number: $contactPhone\n" +
                "Duration: $duration\n" +
                "Balance: $balance"

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
