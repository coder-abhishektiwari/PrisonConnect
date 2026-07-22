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
        val sharedPref = context.getSharedPreferences("PrisonPrefs", Context.MODE_PRIVATE)
        val savedMode = sharedPref.getString("active_sms_provider", null)
        
        val mode = if (savedMode != null) {
            try {
                SmsMode.valueOf(savedMode)
            } catch (e: Exception) {
                SmsConfig.SMS_MODE
            }
        } else {
            SmsConfig.SMS_MODE
        }

        Log.i(TAG, "Initializing active SMS Provider: $mode (${if (savedMode != null) "Stored" else "Default"})")
        
        return when (mode) {
            SmsMode.TWILIO -> TwilioSmsProvider()
            SmsMode.LOG -> LogSmsProvider()
            SmsMode.DEVICE -> DeviceCarrierSmsProvider(context.applicationContext)
        }
    }
}
