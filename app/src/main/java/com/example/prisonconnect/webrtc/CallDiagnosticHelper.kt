package com.example.prisonconnect.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class CallDiagnosticHelper(private val fragment: Fragment) {

    private val context: Context get() = fragment.requireContext()
    private val TAG = "CallDiagnostic"

    sealed class DiagnosticResult {
        object Success : DiagnosticResult()
        data class PermissionMissing(val permissions: List<String>) : DiagnosticResult()
        object NoInternet : DiagnosticResult()
        object MicMuted : DiagnosticResult()
        object MicBusy : DiagnosticResult()
        data class GeneralError(val message: String) : DiagnosticResult()
    }

    data class ResolutionPlan(
        val message: String,
        val actionLabel: String?,
        val action: (() -> Unit)?
    )

    fun performFullDiagnostic(isVideo: Boolean): DiagnosticResult {
        Log.d(TAG, "Starting pre-call diagnostic...")

        // 1. Check Permissions
        val requiredPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        if (isVideo) requiredPermissions.add(Manifest.permission.CAMERA)
        
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.w(TAG, "Permissions missing: $missing")
            return DiagnosticResult.PermissionMissing(missing)
        }

        // 2. Check Network
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connectivity detected")
            return DiagnosticResult.NoInternet
        }

        // 3. Check Audio Hardware
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isMicrophoneMute) {
            Log.w(TAG, "Microphone is currently muted")
            return DiagnosticResult.MicMuted
        }

        // 4. Check if mic is busy (Simplified check)
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION || audioManager.mode == AudioManager.MODE_IN_CALL) {
            Log.w(TAG, "Audio hardware is already in use by another app")
            // This is just a warning in some cases, but for kiosk we want to be strict
            return DiagnosticResult.MicBusy
        }

        Log.d(TAG, "Diagnostic PASSED. All systems nominal.")
        return DiagnosticResult.Success
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    fun getResolutionPlan(result: DiagnosticResult, permissionLauncher: ActivityResultLauncher<Array<String>>): ResolutionPlan {
        return when (result) {
            is DiagnosticResult.PermissionMissing -> ResolutionPlan(
                "Need access to microphone and camera to start the call.",
                "GRANT PERMISSIONS",
                { permissionLauncher.launch(result.permissions.toTypedArray()) }
            )
            is DiagnosticResult.NoInternet -> ResolutionPlan(
                "Internet connection is lost. Please check your network.",
                "RETRY",
                null
            )
            is DiagnosticResult.MicMuted -> ResolutionPlan(
                "Your microphone is muted. Please unmute it from settings.",
                "OK",
                null
            )
            is DiagnosticResult.MicBusy -> ResolutionPlan(
                "Microphone is busy with another task. Please close other apps.",
                "RETRY",
                null
            )
            is DiagnosticResult.GeneralError -> ResolutionPlan(
                "Diagnostic failed: ${result.message}",
                "RETRY",
                null
            )
            DiagnosticResult.Success -> ResolutionPlan("System Ready", null, null)
        }
    }
}
