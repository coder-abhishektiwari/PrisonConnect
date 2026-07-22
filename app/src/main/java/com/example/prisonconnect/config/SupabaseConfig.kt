package com.example.prisonconnect.config

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.plugins.HttpTimeout
import kotlin.time.Duration.Companion.seconds
import io.github.jan.supabase.annotations.SupabaseInternal
import io.ktor.client.engine.okhttp.OkHttpConfig

object SupabaseConfig {

    private const val TAG = "SupabaseConfig"

    const val SUPABASE_URL = "https://vfqufosgzaatydvikuas.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_31-6_doFy14yTBlLKYil7g_Y0E3Gj7I"

    // --- METERED.CA TURN CREDENTIALS ---
    const val METERED_USERNAME = "57a73cc43754da4ba6357595"
    const val METERED_PASSWORD = "q/qfur4DQF/CddhR"

    @OptIn(SupabaseInternal::class)
    val client: SupabaseClient by lazy {
        try {
            Log.d(TAG, "Initializing Supabase Client...")
            createSupabaseClient(
                supabaseUrl = SUPABASE_URL,
                supabaseKey = SUPABASE_ANON_KEY
            ) {
                httpConfig {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30000
                        connectTimeoutMillis = 30000
                        socketTimeoutMillis = 30000
                    }
                    engine {
                        if (this is OkHttpConfig) {
                            config {
                                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            }
                        }
                    }
                }
                
                requestTimeout = 30.seconds

                install(Auth)
                install(Postgrest)
                install(Storage)
                install(Realtime) {
                    heartbeatInterval = 15.seconds
                }
                install(Functions)
            }.also {
                Log.d(TAG, "Supabase Client initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Supabase Client", e)
            throw e
        }
    }
}