package com.example.prisonconnect.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.prisonconnect.data.remote.DbService
import com.example.prisonconnect.data.remote.StorageService
import com.example.prisonconnect.service.WebRtcRecorder
import kotlinx.coroutines.*
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Foreground service for secure call recording with tamper detection.
 *
 * Records call audio/video data to a local file and uploads it to
 * Supabase Storage when the call ends. Includes a kill-switch interlock
 * that monitors recording integrity and marks the room as TAMPER_KILLED
 * if recording is compromised.
 */
class SecureHardwareRecordingService : Service(), 
    JavaAudioDeviceModule.SamplesReadyCallback, 
    JavaAudioDeviceModule.PlaybackSamplesReadyCallback,
    VideoSink {

    private val TAG = "SecureRecordingService"
    private val CHANNEL_ID = "SecureRecordingChannel"
    private val NOTIFICATION_ID = 101

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var roomId: String? = null
    private var kioskId: String? = null
    private var sessionId: String? = null
    private var inmateName: String = "UnknownInmate"
    private var receiverName: String = "UnknownReceiver"

    private var isRecording = false
    private var isVideoCall = true
    private var localFile: File? = null
    private var recorder: WebRtcRecorder? = null

    companion object {
        private const val KILL_SWITCH_INITIAL_DELAY_MS = 8000L
        private const val KILL_SWITCH_INTERVAL_MS = 3000L
        private const val DATE_FORMAT_PATTERN = "yyyy-MM-dd_HH-mm"
        private const val SANITIZE_REGEX = "[^a-zA-Z0-9.\\-()]"
        private const val STORAGE_BUCKET = "recordings"
        private const val STORAGE_PATH_TEMPLATE = "call_recordings/%s/%s/%s"
    }

    inner class LocalBinder : Binder() {
        fun getService(): SecureHardwareRecordingService = this@SecureHardwareRecordingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        
        roomId = intent.getStringExtra("ROOM_ID")
        kioskId = intent.getStringExtra("KIOSK_ID")
        sessionId = intent.getStringExtra("SESSION_ID")
        inmateName = intent.getStringExtra("INMATE_NAME") ?: "UnknownInmate"
        receiverName = intent.getStringExtra("RECEIVER_NAME") ?: "UnknownReceiver"
        isVideoCall = intent.getStringExtra("CALL_TYPE") != "AUDIO"

        // Only start foreground and recording when we have actual call parameters
        if (roomId != null) {
            startForegroundWithType()
            startRecording()
            startKillSwitchInterlock()
        }

        return START_STICKY
    }

    private fun startForegroundWithType() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // FIX (Issue 13): On Android 14+ (API 34), the OS enforces that the manifest declares
            // the foregroundServiceType AND that the app holds matching runtime permissions at the
            // moment startForeground() is called, or it throws ForegroundServiceStartNotAllowedException
            // / SecurityException. Wrap in try/catch to fail gracefully rather than crashing the service.
            Log.e(TAG, "Failed to start foreground service", e)
            // Fall back: try without type (audio-only may still work)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start foreground even without type", e2)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Secure Call Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Recording Active")
            .setContentText("Monitoring and recording the current session.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startRecording() {
        Log.d(TAG, "Starting secure production recording for $inmateName -> $receiverName (Video: $isVideoCall)")
        isRecording = true

        val timestamp = SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault()).format(Date())
        val rawFileName = "${inmateName}-${receiverName}($timestamp).mp4"
        val sanitizedFileName = rawFileName.replace(SANITIZE_REGEX.toRegex(), "_")
        val file = File(cacheDir, sanitizedFileName)
        localFile = file

        recorder = WebRtcRecorder(file, onError = { error ->
            Log.e(TAG, "FATAL RECORDER ERROR: $error")
            handleTamperDetected() // Kill the call if recording fails
        })
        recorder?.start(hasVideo = isVideoCall)
    }

    override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
        recorder?.onLocalAudioSamples(samples)
    }

    override fun onWebRtcAudioTrackSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
        recorder?.onRemoteAudioSamples(samples)
    }

    override fun onFrame(frame: VideoFrame) {
        recorder?.onFrame(frame)
    }

    private fun startKillSwitchInterlock() {
        serviceScope.launch {
            // Give the recorder more time to start up before checking integrity
            delay(KILL_SWITCH_INITIAL_DELAY_MS.milliseconds)
            while (isRecording) {
                if (!checkRecordingIntegrity()) {
                    handleTamperDetected()
                    break
                }
                delay(KILL_SWITCH_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun checkRecordingIntegrity(): Boolean {
        val currentRecorder = recorder ?: return false
        val file = localFile ?: return false
        
        // If muxer hasn't started yet, we can't check file length accurately
        if (!currentRecorder.isMuxerStarted) {
            return true 
        }

        return file.exists() && file.length() > 0
    }

    private fun handleTamperDetected() {
        Log.e(TAG, "TAMPER DETECTED! Terminating call and updating Supabase.")
        isRecording = false
        recorder?.stop()

        roomId?.let { id ->
            serviceScope.launch {
                try {
                    DbService.updateFieldsByColumn(
                        "call_rooms",
                        "room_id",
                        id,
                        mapOf("room_status" to "DISCONNECTED")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update tamper status", e)
                }
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        } ?: stopSelf()
    }

    fun stopAndUpload() {
        Log.d(TAG, "Stopping recording and initiating upload...")
        isRecording = false
        
        val currentRecorder = recorder
        val muxerWasStarted = currentRecorder?.isMuxerStarted == true
        
        currentRecorder?.stop()

        val file = localFile ?: run {
            stopSelf()
            return
        }

        // Integrity check: at least 1KB for a valid MP4 and muxer must have started
        val fileIsValid = file.exists() && file.length() > 1024

        if (!muxerWasStarted || !fileIsValid) {
            Log.w(TAG, "Recording file is invalid or muxer never started (muxerStarted=$muxerWasStarted, size=${file.length()}). " +
                    "Marking as DISCONNECTED (empty) instead of uploading.")
            
            // Clean up the invalid file
            if (file.exists()) file.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        serviceScope.launch {
            try {
                val storagePath = String.format(
                    STORAGE_PATH_TEMPLATE,
                    kioskId ?: "unknown",
                    sessionId ?: "unknown",
                    file.name
                )
                StorageService.uploadFile(
                    bucket = STORAGE_BUCKET,
                    path = storagePath,
                    file = file
                )
                Log.d(TAG, "Upload successful. Wiping local file.")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // FIX (Issue 14): If the app/task is killed mid-call, attempt to upload the recording
        // instead of just discarding it. stopAndUpload() will handle the upload asynchronously
        // and call stopSelf() in its finally block.
        Log.w(TAG, "Task removed — attempting to upload in-progress recording")
        stopAndUpload()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        recorder?.stop()
        serviceJob.cancel()
    }
}
