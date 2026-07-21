package com.example.prisonconnect.config

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

object SupabaseConfig {

    const val SUPABASE_URL = "https://vfqufosgzaatydvikuas.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_31-6_doFy14yTBlLKYil7g_Y0E3Gj7I"

    // --- METERED.CA TURN CREDENTIALS ---
    // Get these from your Metered.ca dashboard (Turn Server -> Credentials)
    const val METERED_API_KEY = "" // Not needed for client side usually if using fixed creds
    const val METERED_USERNAME = "57a73cc43754da4ba6357595"
    const val METERED_PASSWORD = "q/qfur4DQF/CddhR"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Realtime)
            install(Functions)
        }
    }
}