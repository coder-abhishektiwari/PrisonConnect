package com.example.prisonconnect.util

import android.util.Log

/**
 * Structured logging utility for consistent log formatting across PrisonConnect.
 *
 * Provides tagged logging with a consistent format: [TAG] message
 * Default tag is derived from the class name automatically.
 *
 * Usage:
 * ```
 * class MyClass {
 *     private val logger = Logger("MyClass")
 *     fun doSomething() {
 *         logger.d("Doing something") // => [MyClass] Doing something
 *     }
 * }
 * ```
 */
class Logger(private val tag: String?) {

    companion object {
        const val MAX_TAG_LENGTH = 23
        const val DEFAULT_TAG = "Logger"

        /**
         * Creates a Logger with a tag derived from the provided class.
         * Truncates to Android's maximum tag length of 23 characters.
         */
        inline fun <reified T> forClass(): Logger {
            val name = T::class.java.simpleName ?: DEFAULT_TAG
            return Logger(name.take(MAX_TAG_LENGTH))
        }
    }

    private val effectiveTag = tag?.take(MAX_TAG_LENGTH) ?: DEFAULT_TAG

    fun v(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.v(effectiveTag, message, throwable)
        } else {
            Log.v(effectiveTag, message)
        }
    }

    fun d(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.d(effectiveTag, message, throwable)
        } else {
            Log.d(effectiveTag, message)
        }
    }

    fun i(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.i(effectiveTag, message, throwable)
        } else {
            Log.i(effectiveTag, message)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(effectiveTag, message, throwable)
        } else {
            Log.w(effectiveTag, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(effectiveTag, message, throwable)
        } else {
            Log.e(effectiveTag, message)
        }
    }
}
