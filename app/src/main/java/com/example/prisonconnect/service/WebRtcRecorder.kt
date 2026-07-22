package com.example.prisonconnect.service

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import com.example.prisonconnect.util.Logger
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade media recorder for WebRTC calls.
 * 
 * Captures raw PCM audio from local and remote streams, mixes them,
 * and encodes to AAC. Captures remote video frames and encodes to H.264.
 * Muxes everything into a standard MP4 container.
 */
class WebRtcRecorder(
    private val outputFile: File,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720
) : VideoSink {

    private val logger = Logger("WebRtcRecorder")
    
    private var muxer: MediaMuxer? = null
    private var audioEncoder: MediaCodec? = null
    private var videoEncoder: MediaCodec? = null
    
    private var audioTrackIndex = -1
    private var videoTrackIndex = -1
    
    private var isStarted = AtomicBoolean(false)
    private val _isMuxerStarted = AtomicBoolean(false)
    val isMuxerStarted: Boolean get() = _isMuxerStarted.get()
    
    private val audioHandlerThread = HandlerThread("WebRtcAudioEncoder")
    private val videoHandlerThread = HandlerThread("WebRtcVideoEncoder")
    private var audioHandler: Handler? = null
    private var videoHandler: Handler? = null
    
    private var startTimeNs: Long = -1

    // Audio mixing buffers
    private var lastLocalSamples: ByteArray? = null
    private var lastRemoteSamples: ByteArray? = null
    private val audioLock = Object()

    companion object {
        private const val AUDIO_MIME_TYPE = "audio/mp4a-latm"
        private const val VIDEO_MIME_TYPE = "video/avc"
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 64000
        private const val VIDEO_BIT_RATE = 2000000
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 2
    }

    private var hasVideo: Boolean = true

    fun start(hasVideo: Boolean = true) {
        if (isStarted.getAndSet(true)) return
        this.hasVideo = hasVideo
        logger.d("Starting recorder (hasVideo=$hasVideo) to file: ${outputFile.absolutePath}")

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            setupAudioEncoder()
            if (hasVideo) {
                setupVideoEncoder()
            }
            
            audioHandlerThread.start()
            audioHandler = Handler(audioHandlerThread.looper)

            videoHandlerThread.start()
            videoHandler = Handler(videoHandlerThread.looper)
            
            startTimeNs = System.nanoTime()
        } catch (e: Exception) {
            logger.e("Failed to start recorder", e)
            stop()
        }
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        
        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
        audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder?.start()
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, videoWidth, videoHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
        
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
        videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoder?.start()
    }

    /**
     * Called by WebRTC when local microphone samples are ready.
     */
    fun onLocalAudioSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        if (!isStarted.get()) return
        val data = samples.data.copyOf() // Copy to avoid threading issues
        audioHandler?.post {
            synchronized(audioLock) {
                lastLocalSamples = data
                mixAndSendAudio()
            }
        }
    }

    /**
     * Called by WebRTC when remote playback samples are ready.
     */
    fun onRemoteAudioSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        if (!isStarted.get()) return
        val data = samples.data.copyOf() // Copy to avoid threading issues
        audioHandler?.post {
            synchronized(audioLock) {
                lastRemoteSamples = data
                mixAndSendAudio()
            }
        }
    }

    private fun mixAndSendAudio() {
        val local = lastLocalSamples
        val remote = lastRemoteSamples
        
        // We only need ONE side to be ready to start recording
        // If the other side is missing, we fill with silence
        if (local != null || remote != null) {
            val size = local?.size ?: remote?.size ?: return
            val mixed = ByteArray(size)
            
            for (i in 0 until size step 2) {
                if (i + 1 < size) {
                    val s1 = if (local != null) {
                        ((local[i + 1].toInt() shl 8) or (local[i].toInt() and 0xFF)).toShort()
                    } else 0.toShort()
                    
                    val s2 = if (remote != null) {
                        ((remote[i + 1].toInt() shl 8) or (remote[i].toInt() and 0xFF)).toShort()
                    } else 0.toShort()
                    
                    val mixedSample = ((s1 + s2) / 2).toShort()
                    mixed[i] = (mixedSample.toInt() and 0xFF).toByte()
                    mixed[i + 1] = ((mixedSample.toInt() shr 8) and 0xFF).toByte()
                }
            }
            encodeAudio(mixed)
            
            // Clear only what we used
            if (local != null) lastLocalSamples = null
            if (remote != null) lastRemoteSamples = null
        }
    }

    private fun encodeAudio(data: ByteArray) {
        val encoder = audioEncoder ?: return
        try {
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                val presentationTimeUs = (System.nanoTime() - startTimeNs) / 1000
                encoder.queueInputBuffer(inputBufferIndex, 0, data.size, presentationTimeUs, 0)
            }
            drainEncoder(encoder, true)
        } catch (e: Exception) {
            logger.e("Audio encoding error", e)
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (!isStarted.get()) return
        
        frame.retain() // Retain to prevent disposal before worker thread is done
        videoHandler?.post {
            try {
                processVideoFrame(frame)
            } finally {
                frame.release()
            }
        }
    }

    private fun processVideoFrame(frame: VideoFrame) {
        val encoder = videoEncoder ?: return
        try {
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                
                // Convert to I420 and copy to buffer
                val i420 = frame.buffer.toI420() ?: return
                
                try {
                    // Note: This assumes the MediaCodec input buffer is large enough
                    // and expects a packed I420 format (YYYY...UU...VV...)
                    
                    copyPlane(i420.dataY, i420.strideY, i420.width, i420.height, inputBuffer)
                    copyPlane(i420.dataU, i420.strideU, i420.width / 2, i420.height / 2, inputBuffer)
                    copyPlane(i420.dataV, i420.strideV, i420.width / 2, i420.height / 2, inputBuffer)
                    
                    val presentationTimeUs = (System.nanoTime() - startTimeNs) / 1000
                    encoder.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), presentationTimeUs, 0)
                } finally {
                    i420.release()
                }
            }
            drainEncoder(encoder, false)
        } catch (e: Exception) {
            logger.e("Video encoding error", e)
        }
    }

    private fun copyPlane(src: ByteBuffer, stride: Int, width: Int, height: Int, dst: ByteBuffer) {
        if (stride == width) {
            dst.put(src)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                src.position(y * stride)
                src.get(row)
                dst.put(row)
            }
        }
    }

    private fun drainEncoder(encoder: MediaCodec, isAudio: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) throw RuntimeException("Format changed after muxer started")
                val newFormat = encoder.outputFormat
                if (isAudio) audioTrackIndex = muxer!!.addTrack(newFormat)
                else videoTrackIndex = muxer!!.addTrack(newFormat)
                
                val readyToStart = if (hasVideo) {
                    audioTrackIndex != -1 && videoTrackIndex != -1
                } else {
                    audioTrackIndex != -1
                }

                if (readyToStart) {
                    muxer?.start()
                    _isMuxerStarted.set(true)
                    logger.d("Muxer started (hasVideo=$hasVideo)")
                }
            } else if (outputBufferIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                if (isMuxerStarted && outputBuffer != null) {
                    val trackIndex = if (isAudio) audioTrackIndex else videoTrackIndex
                    if (trackIndex != -1) {
                        muxer?.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    fun stop() {
        if (!isStarted.getAndSet(false)) return
        logger.d("Stopping recorder...")
        
        audioHandlerThread.quitSafely()
        videoHandlerThread.quitSafely()
        
        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) { logger.e("Error releasing audio encoder", e) }

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (e: Exception) { logger.e("Error releasing video encoder", e) }

        try {
            if (_isMuxerStarted.get()) {
                muxer?.stop()
            }
        } catch (e: Exception) {
            logger.e("Error stopping muxer", e)
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            audioEncoder = null
            videoEncoder = null
            muxer = null
            _isMuxerStarted.set(false)
        }
    }

    protected fun finalize() {
        if (isStarted.get()) {
            stop()
        }
    }
}
