package com.example.prisonconnect.domain.sms

/**
 * Result of an SMS sending operation.
 */
data class SmsResult(
    val isSuccess: Boolean,
    val message: String? = null,
    val exception: Throwable? = null
)

/**
 * Common interface for SMS delivery providers.
 */
interface SmsProvider {
    /**
     * Sends an SMS message to the specified phone number.
     *
     * @param phoneNumber Recipient's phone number in E.164 format or as required by provider
     * @param message The SMS body content
     * @return [SmsResult] indicating success or failure
     */
    suspend fun sendSms(phoneNumber: String, message: String): SmsResult
}
