package com.example.prisonconnect.config

enum class SmsMode {
    TWILIO, LOG, DEVICE
}

object SmsConfig {
    /**
     * Active SMS delivery mode.
     * Select from: TWILIO, LOG, DEVICE
     */
    val SMS_MODE = SmsMode.LOG

    /**
     * In log mode, show the OTP in the log so testers can enter it manually.
     */
    const val LOG_OTP_IN_DEMO = true

    /**
     * Phone number to use in demo mode when no real contact phone is available.
     */
    const val DEMO_PHONE_NUMBER = "+15550000000"
}