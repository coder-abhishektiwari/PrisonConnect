package com.example.prisonconnect.sms

import com.example.prisonconnect.config.SupabaseConfig
import com.example.prisonconnect.util.Logger
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * SMS provider that uses Twilio via a Supabase Edge Function.
 */
class TwilioSmsProvider : SmsProvider {
    private val logger = Logger("TwilioSmsProvider")

    @Serializable
    private data class SmsPayload(val to: String, val message: String)

    companion object {
        private const val MIN_PHONE_LENGTH = 10
        private const val PHONE_NUMBER_REGEX = "[^0-9]"
    }

    override suspend fun sendSms(phoneNumber: String, message: String): SmsResult = withContext(Dispatchers.IO) {
        try {
            val cleanPhone = phoneNumber.replace(PHONE_NUMBER_REGEX.toRegex(), "")
            if (cleanPhone.isBlank() || cleanPhone.length < MIN_PHONE_LENGTH) {
                return@withContext SmsResult(false, "Invalid phone number: $phoneNumber")
            }

            logger.d("Invoking send-sms Edge Function for: $cleanPhone")

            SupabaseConfig.client.functions.invoke(
                "send-sms",
                SmsPayload(cleanPhone, message)
            )

            logger.d("SMS Edge Function invoked successfully for $cleanPhone")
            SmsResult(true)
        } catch (e: Exception) {
            logger.e("Failed to send SMS via Twilio Edge Function", e)
            SmsResult(false, e.message, e)
        }
    }
}
