package com.example.prisonconnect.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


@Serializable
data class CallRoom(
    @SerialName("id")
    val id: String = "",
    @SerialName("room_id")
    val room_id: String = "",
    @SerialName("kiosk_id")
    val kiosk_id: String = "",
    @SerialName("inmate_id")
    val inmate_id: String = "",
    @SerialName("call_type")
    val call_type: String = "",
    @SerialName("room_status")
    val room_status: String = "",
    @SerialName("receiver_phone")
    val receiver_phone: String? = null,
    @SerialName("receiverPhone")
    val receiverPhone: String? = null,
    @SerialName("webrtc_signaling")
    val webrtc_signaling: WebRtcSignaling? = null
)

@Serializable
data class WebRtcSignaling(
    @SerialName("offer")
    val offer: SdpInfo? = null,
    @SerialName("answer")
    val answer: SdpInfo? = null,
    @SerialName("iceCandidates")
    val iceCandidates: List<IceCandidateInfo> = emptyList()
)

@Serializable(with = SdpInfoSerializer::class)
data class SdpInfo(
    @SerialName("type")
    val type: String = "",
    @SerialName("sdp")
    val sdp: String = ""
)

@Serializable
data class IceCandidateInfo(
    @SerialName("sdpMid")
    val sdpMid: String = "",
    @SerialName("sdpMLineIndex")
    val sdpMLineIndex: Int = 0,
    @SerialName("sdp")
    val sdp: String = ""
)

/**
 * Custom serializer for SdpInfo that handles:
 * - Proper object: {"type": "offer", "sdp": "..."}
 * - Empty string: "" (treats as null)
 * - Null: null
 */
object SdpInfoSerializer : KSerializer<SdpInfo?> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("SdpInfo", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SdpInfo? {
        val input = decoder as? JsonDecoder ?: throw SerializationException("This class can only be deserialized from JSON")
        val element = input.decodeJsonElement()

        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (element.isString && element.content.isEmpty()) {
                    null
                } else {
                    throw SerializationException("Expected object or null for SdpInfo, got string: ${element.content}")
                }
            }
            is JsonObject -> {
                val type = element["type"]?.jsonPrimitive?.content ?: ""
                val sdp = element["sdp"]?.jsonPrimitive?.content ?: ""
                SdpInfo(type, sdp)
            }
            else -> throw SerializationException("Expected object or null for SdpInfo, got: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: SdpInfo?) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("This class can only be serialized to JSON")
        if (value == null) {
            jsonEncoder.encodeJsonElement(JsonNull)
        } else {
            jsonEncoder.encodeJsonElement(buildJsonObject {
                put("type", value.type)
                put("sdp", value.sdp)
            })
        }
    }
}