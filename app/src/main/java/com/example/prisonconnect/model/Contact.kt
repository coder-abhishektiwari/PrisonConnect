package com.example.prisonconnect.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    @SerialName("contact_id")
    val contact_id: String = "",
    @SerialName("associated_inmate_id")
    val associated_inmate_id: String = "",
    @SerialName("full_name")
    val full_name: String = "",
    @SerialName("phone_number")
    val phone_number: String = "",
    @SerialName("relationship_type")
    val relationship_type: String = "" // "FAMILY", "LAWYER", "FACILITY_EMERGENCY"
)