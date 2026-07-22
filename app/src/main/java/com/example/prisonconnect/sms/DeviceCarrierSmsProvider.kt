package com.example.prisonconnect.sms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * SMS provider that sends SMS using the device's native carrier network.
 */
class DeviceCarrierSmsProvider(private val context: Context) : SmsProvider {
    private val logger = Logger("DeviceSmsProvider")

    companion object {
        private const val SENT_ACTION = "SMS_SENT_ACTION"
        private const val DELIVERED_ACTION = "SMS_DELIVERED_ACTION"
        private const val TIMEOUT_MS = 10000L
    }

    override suspend fun sendSms(phoneNumber: String, message: String): SmsResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            logger.e("SEND_SMS permission not granted")
            return SmsResult(false, "Permission SEND_SMS missing")
        }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return SmsResult(false, "SmsManager not available")

        val requestId = UUID.randomUUID().toString()
        val sentIntent = PendingIntent.getBroadcast(
            context, 
            requestId.hashCode(), 
            Intent(SENT_ACTION).putExtra("id", requestId), 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val sentDeferred = CompletableDeferred<SmsResult>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == SENT_ACTION && intent.getStringExtra("id") == requestId) {
                    when (resultCode) {
                        android.app.Activity.RESULT_OK -> {
                            logger.d("SMS sent successfully")
                            sentDeferred.complete(SmsResult(true))
                        }
                        else -> {
                            logger.e("SMS send failed with code: $resultCode")
                            sentDeferred.complete(SmsResult(false, "Send failed: $resultCode"))
                        }
                    }
                    context?.unregisterReceiver(this)
                }
            }
        }

        val filter = IntentFilter(SENT_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        try {
            logger.d("Sending SMS via carrier to $phoneNumber")
            smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, null)
            
            return withTimeoutOrNull(TIMEOUT_MS) {
                sentDeferred.await()
            } ?: run {
                context.unregisterReceiver(receiver)
                SmsResult(false, "Send timeout")
            }
        } catch (e: Exception) {
            logger.e("Exception sending carrier SMS", e)
            try { context.unregisterReceiver(receiver) } catch (ex: Exception) {}
            return SmsResult(false, e.message, e)
        }
    }
}
