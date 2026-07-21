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
import kotlin.time.Duration.Companion.milliseconds

class SecureHardwareRecordingService : Service() {

    private val TAG = "SecureRecordingService abhishek"
    private val CHANNEL_ID = "SecureRecordingChannel"
    private val NOTIFICATION_ID = 101

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var roomId: String? = null
    private var kioskId: String? = null
    private var sessionId: String? = null

    private var isRecording = false
    private var localFile: File? = null

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

        // 🌟 Fixed: Android Oreo and above ke background bounds safely handling target definitions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        startRecording()
        startKillSwitchInterlock()

        return START_STICKY
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
        Log.d("TAG", "Starting secure recording...")
        isRecording = true

        val fileName = "rec_${System.currentTimeMillis()}.mp4"
        localFile = File(cacheDir, fileName)

        serviceScope.launch {
            try {
                FileOutputStream(localFile).use { fos ->
                    while (isRecording) {
                        fos.write("VIDEO_AUDIO_STREAM_DATA".toByteArray())
                        fos.flush()
                        delay(1000.milliseconds)
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

    private fun startKillSwitchInterlock() {
        serviceScope.launch {
            while (isRecording) {
                delay(2500.milliseconds)
                if (!checkRecordingIntegrity()) {
                    handleTamperDetected()
                    break
                }
            }
        }
    }

    private fun checkRecordingIntegrity(): Boolean {
        val file = localFile ?: return false
        return file.exists() && file.length() > 0
    }

    private fun handleTamperDetected() {
        Log.e(TAG, "TAMPER DETECTED! Terminating call and updating Supabase.")
        isRecording = false

        roomId?.let { id ->
            serviceScope.launch {
                try {
                    DbService.updateFieldsByColumn("call_rooms", "room_id", id, mapOf("room_status" to "TAMPER_KILLED"))
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
                val storagePath = "call_recordings/$kioskId/$sessionId/${file.name}"
                // 🌟 Supabase Integration handles standard java File path bytes conversion safely now
                StorageService.uploadFile(
                    bucket = "recordings",
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