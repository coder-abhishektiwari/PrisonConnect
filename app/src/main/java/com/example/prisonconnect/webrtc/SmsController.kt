package com.example.prisonconnect.webrtc

import com.example.prisonconnect.config.SmsConfig
import com.example.prisonconnect.config.SupabaseConfig
import com.example.prisonconnect.util.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Unified SMS Controller.
 *
 * - Production mode: sends SMS via Supabase Edge Function.
 * - Demo mode: logs the SMS to console, no network call.
 *
 * This keeps all SMS routing in one place so adding a new provider
 * (Twilio, MSG91, etc.) only requires changing this file.
 */
class SmsController {

    private val logger = Logger("SmsController")

    @Serializable
    private data class SmsPayload(val to: String, val message: String)

    companion object {
        private const val MIN_PHONE_LENGTH = 10
        private const val PHONE_NUMBER_REGEX = "[^0-9]"
    }

    /**
     * Sends an SMS message.
     *
     * @param toPhone The recipient's phone number (may include country code prefix)
     * @param message The SMS body content
     * @return Result containing true if successfully sent/logged, or failure with exception
     */
    suspend fun sendSmsViaSupabase(toPhone: String, message: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (SmsConfig.DEMO_MODE) {
                    logger.d("Demo mode: sendSms invoked for '$toPhone'")
                    DemoSmsSender.sendSms(toPhone, message)
                } else {
                    sendProductionSms(toPhone, message)
                }
            }
        }

    private suspend fun sendProductionSms(toPhone: String, message: String): Boolean {
        val cleanPhone = toPhone.replace(PHONE_NUMBER_REGEX.toRegex(), "")
        require(cleanPhone.isNotBlank() && cleanPhone.length >= MIN_PHONE_LENGTH) {
            "Invalid phone number: $toPhone (cleaned: $cleanPhone)"
        }

        logger.d("Invoking send-sms Edge Function for: $cleanPhone")

        SupabaseConfig.client.functions.invoke(
            "send-sms",
            SmsPayload(cleanPhone, message)
        )

        logger.d("SMS Edge Function invoked successfully for $cleanPhone")
        return true
    }

    /**
     * No-op kept for backward compatibility.
     * Receivers are no longer registered locally since SMS is routed via Supabase/Demo.
     */
    fun unregisterReceivers() {
        // No-op: SMS is sent via Supabase Edge Function or Demo logger
    }
}
