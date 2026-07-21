package com.example.prisonconnect.webrtc

import android.util.Log
import com.example.prisonconnect.config.SupabaseConfig
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class SmsRequest(val to: String, val message: String)

class SmsController {

    /**
     * Sends an SMS message using a Supabase Edge Function.
     * This bypasses local carrier restrictions and dual-SIM issues.
     * 
     * @param toPhone The recipient's phone number.
     * @param message The SMS body content.
     * @return Result containing true if successfully invoked, or failure.
     */
    suspend fun sendSmsViaSupabase(toPhone: String, message: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPhone = toPhone.replace("[^0-9]".toRegex(), "")
            
            if (cleanPhone.isBlank() || cleanPhone.length < 10) {
                Log.e("SmsController", "Invalid phone number: $toPhone (cleaned: $cleanPhone)")
                throw IllegalArgumentException("Invalid phone number: $toPhone")
            }

            Log.d("SmsController", "Invoking send-sms Edge Function for: $cleanPhone")
            
            SupabaseConfig.client.functions.invoke("send-sms", SmsRequest(cleanPhone, message))
            
            Log.d("SmsController", "SMS Edge Function invoked successfully for $cleanPhone")
            true
        }
    }

    // Keep this for backward compatibility during refactoring if needed, or remove it
    // Requirements say "Remove all Android SmsManager... from SmsController.kt"
    fun unregisterReceivers() {
        // No-op: receivers are no longer used
    }
}