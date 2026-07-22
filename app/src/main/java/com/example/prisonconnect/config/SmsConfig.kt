package com.example.prisonconnect.config

object SmsConfig {
    /**
     * When true, SMS messages are only logged to console and NOT actually sent.
     * Use this for demo/testing without spending SMS credits or triggering rate limits.
     */
    const val DEMO_MODE = true

    /**
     * In demo mode, show the OTP in the log so testers can enter it manually.
     */
    const val LOG_OTP_IN_DEMO = true

    /**
     * Phone number to use in demo mode when no real contact phone is available.
     */
    const val DEMO_PHONE_NUMBER = "+15550000000"
}