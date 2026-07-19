package com.example.prisonconnect.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RealtimePayload(
    val event: String,
    val payload: JsonElement
)

@Serializable
data class WebRtcOfferPayload(
    val sender: String,
    val offer: Map<String, String>
)

@Serializable
data class WebRtcCandidatePayload(
    val sender: String,
    val candidate: Map<String, JsonElement>
)