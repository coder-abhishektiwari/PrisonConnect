package com.example.prisonconnect.webrtc

import android.util.Log
import com.example.prisonconnect.config.SmsConfig
import com.example.prisonconnect.util.Logger

/**
 * Demo SMS sender that logs messages instead of sending real SMS.
 * Used when [SmsConfig.DEMO_MODE] is true.
 */
object DemoSmsSender {

    private const val TAG = "DemoSmsSender"

    fun sendSms(toPhone: String, message: String): Boolean {
        val displayPhone = if (SmsConfig.DEMO_MODE && toPhone.isBlank()) {
            SmsConfig.DEMO_PHONE_NUMBER
        } else {
            toPhone
        }

        Log.i(TAG, "============================================")
        Log.i(TAG, "DEMO MODE - SMS NOT ACTUALLY SENT")
        Log.i(TAG, "============================================")
        Log.i(TAG, "To: $displayPhone")
        Log.i(TAG, "Message: $message")
        Log.i(TAG, "============================================")

        // Extract OTP if present in message and log it for testing
        if (SmsConfig.LOG_OTP_IN_DEMO) {
            val otpRegex = "\\d{6}".toRegex()
            val otpMatch = otpRegex.find(message)
            if (otpMatch != null) {
                Log.i(TAG, "DEMO OTP: ${otpMatch.value}")
            }
        }

        return true
    }
}