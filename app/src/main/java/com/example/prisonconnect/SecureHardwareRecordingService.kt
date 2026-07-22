package com.example.prisonconnect

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.prisonconnect.repository.DbService
import com.example.prisonconnect.repository.StorageService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
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
 *
 * This service runs as a foreground service with appropriate type flags
 * for camera and microphone access on Android 10+.
 */
class SecureHardwareRecordingService : Service() {

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
    private var localFile: File? = null

    companion object {
        private const val RECORDING_WRITE_INTERVAL_MS = 1000L
        private const val KILL_SWITCH_INTERVAL_MS = 2500L
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
        roomId = intent?.getStringExtra("ROOM_ID")
        kioskId = intent?.getStringExtra("KIOSK_ID")
        sessionId = intent?.getStringExtra("SESSION_ID")
        inmateName = intent?.getStringExtra("INMATE_NAME") ?: "UnknownInmate"
        receiverName = intent?.getStringExtra("RECEIVER_NAME") ?: "UnknownReceiver"

        startForegroundWithType()

        startRecording()
        startKillSwitchInterlock()

        return START_STICKY
    }

    /**
     * Starts the service as a foreground service with appropriate type flags.
     * On Android 14+ (U), camera and microphone types are required for media recording.
     */
    private fun startForegroundWithType() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

    /**
     * Starts the recording loop that writes simulated stream data to a local file.
     * In a production system, this would capture actual audio/video frames.
     */
    private fun startRecording() {
        Log.d(TAG, "Starting secure recording for $inmateName -> $receiverName")
        isRecording = true

        val timestamp = SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault()).format(Date())
        val rawFileName = "${inmateName}-${receiverName}($timestamp).mp4"
        val sanitizedFileName = rawFileName.replace(SANITIZE_REGEX.toRegex(), "_")
        localFile = File(cacheDir, sanitizedFileName)

        serviceScope.launch {
            try {
                FileOutputStream(localFile).use { fos ->
                    while (isRecording) {
                        fos.write("VIDEO_AUDIO_STREAM_DATA".toByteArray())
                        fos.flush()
                        delay(RECORDING_WRITE_INTERVAL_MS.milliseconds)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Recording loop cancelled normally.")
            } catch (e: Exception) {
                Log.e(TAG, "Recording ingestion failed: ${e.message}")
                handleTamperDetected()
            }
        }
    }

    /**
     * Periodic integrity check that monitors the recording file.
     * If the file is missing or empty, triggers tamper detection.
     */
    private fun startKillSwitchInterlock() {
        serviceScope.launch {
            while (isRecording) {
                delay(KILL_SWITCH_INTERVAL_MS.milliseconds)
                if (!checkRecordingIntegrity()) {
                    handleTamperDetected()
                    break
                }
            }
        }
    }

    /**
     * Checks that the recording file exists and has content.
     *
     * @return true if the file exists and has non-zero length
     */
    private fun checkRecordingIntegrity(): Boolean {
        val file = localFile ?: return false
        return file.exists() && file.length() > 0
    }

    /**
     * Handles a tamper detection event by marking the room as TAMPER_KILLED
     * and stopping the service.
     */
    private fun handleTamperDetected() {
        Log.e(TAG, "TAMPER DETECTED! Terminating call and updating Supabase.")
        isRecording = false

        roomId?.let { id ->
            serviceScope.launch {
                try {
                    DbService.updateFieldsByColumn(
                        "call_rooms",
                        "room_id",
                        id,
                        mapOf("room_status" to "TAMPER_KILLED")
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

    /**
     * Stops recording and uploads the recorded file to Supabase Storage.
     * Called by the activity/fragment when the call ends normally.
     */
    fun stopAndUpload() {
        Log.d(TAG, "Stopping recording and initiating upload...")
        isRecording = false

        val file = localFile ?: run {
            stopSelf()
            return
        }

        if (!serviceJob.isActive) {
            Log.w(TAG, "Scope already inactive, skipping upload")
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
                    Log.d(TAG, "Local file wiped successfully.")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Upload job cancelled.")
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
        Log.d(TAG, "App task removed, stopping service...")
        isRecording = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service being destroyed...")
        isRecording = false
        serviceJob.cancel()
    }
}