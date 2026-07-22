package com.example.prisonconnect.data.sms

import android.util.Log
import com.example.prisonconnect.config.SmsConfig
import com.example.prisonconnect.domain.sms.SmsProvider
import com.example.prisonconnect.domain.sms.SmsResult
import com.example.prisonconnect.util.Logger

/**
 * SMS provider that only logs the message to Logcat.
 * Useful for development and testing.
 */
class LogSmsProvider : SmsProvider {
    private val logger = Logger("LogSmsProvider")

    override suspend fun sendSms(phoneNumber: String, message: String): SmsResult {
        val displayPhone = if (phoneNumber.isBlank()) {
            SmsConfig.DEMO_PHONE_NUMBER
        } else {
            phoneNumber
        }

        Log.i("LogSmsProvider", "============================================")
        Log.i("LogSmsProvider", "LOG MODE - SMS NOT ACTUALLY SENT")
        Log.i("LogSmsProvider", "============================================")
        Log.i("LogSmsProvider", "To: $displayPhone")
        Log.i("LogSmsProvider", "Message: $message")
        Log.i("LogSmsProvider", "============================================")

        // Extract OTP if present in message and log it for testing
        if (SmsConfig.LOG_OTP_IN_DEMO) {
            val otpRegex = "\\d{6}".toRegex()
            val otpMatch = otpRegex.find(message)
            if (otpMatch != null) {
                Log.i("LogSmsProvider", "LOG OTP: ${otpMatch.value}")
            }
        }

        return SmsResult(true, "Logged successfully")
    }
}
