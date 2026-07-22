package com.example.prisonconnect.webrtc

import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.*
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.concurrent.TimeUnit

/**
 * Periodically collects and logs WebRTC statistics for a [PeerConnection].
 * 
 * Monitors critical metrics such as:
 * - ICE connection and gathering states
 * - Round-trip time (RTT) and jitter
 * - Packet loss and packets sent/received
 * - Bitrate (audio/video)
 * - Video FPS and frame drops
 */
class WebRtcStatsLogger(
    private val peerConnection: PeerConnection,
    private val scope: CoroutineScope
) {
    private val logger = Logger("WebRtcStats")
    private var statsJob: Job? = null

    private var lastBytesReceived: Long = 0
    private var lastBytesSent: Long = 0
    private var lastTimestampUs: Long = 0

    companion object {
        private const val STATS_INTERVAL_MS = 2000L
    }

    /**
     * Starts periodic statistics collection.
     */
    fun start() {
        if (statsJob?.isActive == true) return
        
        logger.d("Starting WebRTC stats monitoring")
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                collectStats()
            }
        }
    }

    /**
     * Stops statistics collection.
     */
    fun stop() {
        statsJob?.cancel()
        statsJob = null
        logger.d("Stopped WebRTC stats monitoring")
    }

    private fun collectStats() {
        peerConnection.getStats { report ->
            processReport(report)
        }
    }

    private fun processReport(report: RTCStatsReport) {
        val statsMap = report.statsMap
        val sb = StringBuilder("\n--- WebRTC Stats Report ---\n")

        // 1. ICE State
        sb.append("ICE State: ${peerConnection.iceConnectionState()}\n")
        
        // 2. Candidate Pair (RTT & Available Bitrate)
        statsMap.values.filter { it.type == "candidate-pair" && it.members["state"] == "succeeded" }
            .forEach { stats ->
                val rtt = stats.members["currentRoundTripTime"] as? Double ?: 0.0
                val availableBitrate = (stats.members["availableOutgoingBitrate"] as? Double ?: 0.0) / 1000.0
                sb.append("RTT: ${(rtt * 1000).toInt()}ms | Available Outgoing: ${availableBitrate.toInt()} kbps\n")
            }

        // 3. Inbound RTP (Remote -> Kiosk)
        statsMap.values.filter { it.type == "inbound-rtp" }.forEach { stats ->
            val kind = stats.members["kind"] as? String ?: "unknown"
            val packetsLost = stats.members["packetsLost"] as? Long ?: 0
            val jitter = stats.members["jitter"] as? Double ?: 0.0
            
            if (kind == "video") {
                val framesDecoded = stats.members["framesDecoded"] as? Long ?: 0
                val framesDropped = stats.members["framesDropped"] as? Long ?: 0
                val width = stats.members["frameWidth"] as? Long ?: 0
                val height = stats.members["frameHeight"] as? Long ?: 0
                sb.append("IN VIDEO: ${width}x${height} | Decoded: $framesDecoded | Dropped: $framesDropped | Lost: $packetsLost | Jitter: ${(jitter * 1000).toInt()}ms\n")
            } else if (kind == "audio") {
                sb.append("IN AUDIO: Lost: $packetsLost | Jitter: ${(jitter * 1000).toInt()}ms\n")
            }
        }

        // 4. Outbound RTP (Kiosk -> Remote)
        statsMap.values.filter { it.type == "outbound-rtp" }.forEach { stats ->
            val kind = stats.members["kind"] as? String ?: "unknown"
            if (kind == "video") {
                val framesSent = stats.members["framesSent"] as? Long ?: 0
                val width = stats.members["frameWidth"] as? Long ?: 0
                val height = stats.members["frameHeight"] as? Long ?: 0
                sb.append("OUT VIDEO: ${width}x${height} | Frames: $framesSent\n")
            }
        }

        // 5. Overall Bitrate Calculation
        val currentBytesReceived = statsMap.values.filter { it.type == "inbound-rtp" }
            .sumOf { it.members["bytesReceived"] as? Long ?: 0L }
        val currentBytesSent = statsMap.values.filter { it.type == "outbound-rtp" }
            .sumOf { it.members["bytesSent"] as? Long ?: 0L }
        val currentTimestampUs = report.timestampUs

        if (lastTimestampUs > 0) {
            val deltaMs = (currentTimestampUs - lastTimestampUs) / 1000.0
            val rxBitrate = ((currentBytesReceived - lastBytesReceived) * 8.0) / deltaMs // kbps
            val txBitrate = ((currentBytesSent - lastBytesSent) * 8.0) / deltaMs // kbps
            sb.append("BITRATE: RX: ${rxBitrate.toInt()} kbps | TX: ${txBitrate.toInt()} kbps\n")
        }

        lastBytesReceived = currentBytesReceived
        lastBytesSent = currentBytesSent
        lastTimestampUs = currentTimestampUs

        sb.append("---------------------------")
        logger.d(sb.toString())
    }
}
