package com.example.prisonconnect.data.sms

import android.content.Context
import com.example.prisonconnect.config.SmsConfig
import com.example.prisonconnect.config.SmsMode
import android.util.Log
import com.example.prisonconnect.domain.sms.SmsProvider

/**
 * Factory for creating the active [SmsProvider] based on [SmsConfig].
 */
object SmsProviderFactory {
    private const val TAG = "SmsProviderFactory"

    /**
     * Creates and returns the active [SmsProvider] implementation.
     *
     * @param context Application context (required for device carrier mode)
     */
    fun createProvider(context: Context): SmsProvider {
        val mode = SmsConfig.SMS_MODE
        Log.i(TAG, "Initializing active SMS Provider: $mode")
        
        return when (mode) {
            SmsMode.TWILIO -> TwilioSmsProvider()
            SmsMode.LOG -> LogSmsProvider()
            SmsMode.DEVICE -> DeviceCarrierSmsProvider(context.applicationContext)
        }
    }
}
