package com.example.prisonconnect.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.example.prisonconnect.util.Logger

/**
 * Performs pre-call hardware and permissions diagnostics.
 *
 * Checks the following before a call can proceed:
 * 1. Required permissions (microphone, camera for video calls)
 * 2. Network connectivity (WiFi, cellular, or ethernet)
 * 3. Microphone mute status
 * 4. Audio hardware availability (not in GSM call)
 *
 * @property context Android context for system services
 */
class CallDiagnosticHelper(private val context: Context) {

    private val logger = Logger("CallDiagnostic")

    companion object {
        private val AUDIO_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    }

    /**
     * Represents the result of a diagnostic check.
     */
    sealed class DiagnosticResult {
        /** All checks passed, call can proceed. */
        object Success : DiagnosticResult()

        /** One or more required permissions are not granted. */
        data class PermissionMissing(val permissions: List<String>) : DiagnosticResult()

        /** No active internet connection detected. */
        object NoInternet : DiagnosticResult()

        /** Microphone is currently muted by the system. */
        object MicMuted : DiagnosticResult()

        /** Audio hardware is in use by a GSM call. */
        object MicBusy : DiagnosticResult()

        /** A general error occurred during the diagnostic. */
        data class GeneralError(val message: String) : DiagnosticResult()
    }

    /**
     * Describes a plan to resolve a diagnostic failure for the user.
     *
     * @property message Human-readable explanation for the user
     * @property actionLabel Optional label for an action button (null if no action needed)
     * @property action Optional action to execute (null if no action available)
     */
    data class ResolutionPlan(
        val message: String,
        val actionLabel: String?,
        val action: (() -> Unit)?
    )

    /**
     * Runs a full pre-call diagnostic check.
     *
     * @param isVideo Whether the call requires video (adds camera permission check)
     * @return [DiagnosticResult.Success] if all checks pass, or a specific failure reason
     */
    fun performFullDiagnostic(isVideo: Boolean): DiagnosticResult {
        logger.d("Starting pre-call diagnostic (isVideo=$isVideo)")

        // 1. Check permissions
        val requiredPermissions = mutableListOf<String>().apply {
            addAll(AUDIO_PERMISSIONS.map { it })
            if (isVideo) {
                add(Manifest.permission.CAMERA)
            }
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            logger.w("Permissions missing: $missingPermissions")
            return DiagnosticResult.PermissionMissing(missingPermissions)
        }

        // 2. Check network connectivity
        if (!isNetworkAvailable()) {
            logger.w("No internet connectivity detected")
            return DiagnosticResult.NoInternet
        }

        // 3. Check audio hardware
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (audioManager.isMicrophoneMute) {
            logger.w("Microphone is currently muted")
            return DiagnosticResult.MicMuted
        }

        // 4. Check if microphone is busy with GSM call
        if (audioManager.mode == AudioManager.MODE_IN_CALL) {
            logger.w("Audio hardware is already in use by a GSM call")
            return DiagnosticResult.MicBusy
        }

        logger.d("Diagnostic PASSED. All systems nominal.")
        return DiagnosticResult.Success
    }

    /**
     * Checks if the device has an active network connection.
     *
     * @return true if WiFi, cellular, or ethernet is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Returns a [ResolutionPlan] describing how to resolve the given [result].
     *
     * @param result The diagnostic result to create a plan for
     * @param permissionLauncher The activity result launcher for requesting permissions
     * @return A [ResolutionPlan] with message and optional action
     */
    fun getResolutionPlan(
        result: DiagnosticResult,
        permissionLauncher: ActivityResultLauncher<Array<String>>
    ): ResolutionPlan {
        return when (result) {
            is DiagnosticResult.PermissionMissing -> ResolutionPlan(
                message = "Need access to microphone and camera to start the call.",
                actionLabel = "GRANT PERMISSIONS",
                action = { permissionLauncher.launch(result.permissions.toTypedArray()) }
            )
            is DiagnosticResult.NoInternet -> ResolutionPlan(
                message = "Internet connection is lost. Please check your network.",
                actionLabel = "RETRY",
                action = null
            )
            is DiagnosticResult.MicMuted -> ResolutionPlan(
                message = "Your microphone is muted. Please unmute it from settings.",
                actionLabel = "OK",
                action = null
            )
            is DiagnosticResult.MicBusy -> ResolutionPlan(
                message = "Microphone is busy with another task. Please close other apps.",
                actionLabel = "RETRY",
                action = null
            )
            is DiagnosticResult.GeneralError -> ResolutionPlan(
                message = "Diagnostic failed: ${result.message}",
                actionLabel = "RETRY",
                action = null
            )
            DiagnosticResult.Success -> ResolutionPlan(
                message = "System Ready",
                actionLabel = null,
                action = null
            )
        }
    }
}