package com.example.prisonconnect.ui.call

import android.content.Context
import com.example.prisonconnect.domain.sms.SmsProvider
import com.example.prisonconnect.data.sms.SmsProviderFactory
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified SMS Controller.
 *
 * This controller delegates SMS delivery to a modular [SmsProvider]
 * selected via configuration.
 */
class SmsController(context: Context) {

    private val logger = Logger("SmsController")
    private val provider: SmsProvider = SmsProviderFactory.createProvider(context)

    /**
     * Sends an SMS message using the active provider.
     *
     * @param toPhone The recipient's phone number
     * @param message The SMS body content
     * @return Result containing true if successfully sent/logged, or failure with exception
     */
    suspend fun sendSmsViaSupabase(toPhone: String, message: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                logger.d("Sending SMS to '$toPhone' using ${provider.javaClass.simpleName}")
                val result = provider.sendSms(toPhone, message)
                if (!result.isSuccess) {
                    throw Exception(result.message ?: "Unknown SMS delivery error")
                }
                true
            }
        }

}
