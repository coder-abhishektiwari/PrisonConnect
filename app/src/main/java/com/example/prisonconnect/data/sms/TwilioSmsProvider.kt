package com.example.prisonconnect.data.sms

import com.example.prisonconnect.config.SupabaseConfig
import com.example.prisonconnect.domain.sms.SmsProvider
import com.example.prisonconnect.domain.sms.SmsResult
import com.example.prisonconnect.util.Logger
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * SMS provider that uses Twilio via a Supabase Edge Function.
 * Optimized for production-grade error reporting and status tracking.
 */
class TwilioSmsProvider : SmsProvider {
    private val logger = Logger("TwilioSmsProvider")

    @Serializable
    private data class SmsPayload(val to: String, val message: String)

    @Serializable
    private data class SmsResponse(
        val success: Boolean,
        val message: String? = null,
        val sid: String? = null,
        val status: String? = null,
        val code: Int? = null
    )

    companion object {
        private const val MIN_PHONE_LENGTH = 10
        private const val PHONE_NUMBER_REGEX = "[^0-9]"
    }

    override suspend fun sendSms(phoneNumber: String, message: String): SmsResult = withContext(Dispatchers.IO) {
        try {
            val cleanPhone = phoneNumber.replace(PHONE_NUMBER_REGEX.toRegex(), "")
            if (cleanPhone.isBlank() || cleanPhone.length < MIN_PHONE_LENGTH) {
                return@withContext SmsResult(false, "Invalid phone number format")
            }

            logger.d("Invoking send-sms for: $cleanPhone")

            // Supabase functions.invoke returns a Ktor HttpResponse
            val response: HttpResponse = SupabaseConfig.client.functions.invoke(
                "send-sms",
                SmsPayload(cleanPhone, message)
            )

            val responseBody = response.bodyAsText()
            
            // Parse standardized JSON envelope
            val body = try {
                Json.decodeFromString<SmsResponse>(responseBody)
            } catch (e: Exception) {
                logger.e("Failed to parse SMS response: $responseBody", e)
                null
            }

            if (body == null) {
                return@withContext SmsResult(false, "SMS Gateway unreachable (Status: ${response.status.value})")
            }

            if (!body.success) {
                val rawMsg = body.message ?: ""
                val errorCode = body.code ?: -1
                
                val userFriendlyMsg = when {
                    errorCode == 21608 || rawMsg.contains("unverified", ignoreCase = true) -> 
                        "failed to send sms to unverified number. Try to change provider through home screen or share manually."
                    errorCode == 21211 || rawMsg.contains("invalid", ignoreCase = true) -> 
                        "The phone number is invalid. Please check and try again."
                    else -> body.message ?: "Twilio rejected the request"
                }
                
                logger.w("SMS Failed ($errorCode): $rawMsg")
                return@withContext SmsResult(false, userFriendlyMsg)
            }

            logger.d("SMS confirm success: ${body.sid} [Status: ${body.status}]")
            return@withContext SmsResult(true, body.message ?: "SMS accepted for delivery")

        } catch (e: Exception) {
            logger.e("Twilio Function execution failed", e)
            SmsResult(
                isSuccess = false,
                message = e.localizedMessage ?: "Unable to connect to SMS service"
            )
        }
    }
}
