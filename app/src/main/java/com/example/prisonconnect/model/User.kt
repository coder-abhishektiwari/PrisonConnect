package com.example.prisonconnect.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    @SerialName("id")
    val id: String = "",
    @SerialName("full_name")
    val full_name: String = "",
    @SerialName("pin_hash")
    val pin_hash: String = "",
    @SerialName("balance_remaining_seconds")
    val balance_remaining_seconds: Long = 0,
    @SerialName("account_status")
    val account_status: String = "",
    @SerialName("prisoner_id")
    val prisoner_id: String = "",
    @SerialName("jail_name")
    val jail_name: String = ""
)