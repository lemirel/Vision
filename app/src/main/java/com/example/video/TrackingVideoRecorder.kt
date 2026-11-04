package com.example.video

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.Arrays
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.example.data.model.DetectedObject
import com.example.data.model.FrameTelemetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * High-performance Video Recorder that burns real-time MediaPipe object detections
 * (bounding boxes, classification text, confidence scores, tracking tags, and reticles)
 * directly into the saved MP4 video frames using MediaCodec and MediaMuxer.
 *
 * Ensures:
 * - Standard video/avc (H.264 Baseline) and audio/mp4a-latm (AAC) streams.
 * - Reliable file finalization, closing, and flushing of the moov atom header.
 * - Detections remain permanently visible in saved videos across all players and shared apps.
 * - Android 10+ Scoped Storage compliance (no deprecated _data mutation).
 */
class TrackingVideoRecorder(
    private val context: Context
) {
    companion object {
        private const val TAG = "TrackingVideoRecorder"
        private const val MIME_TYPE_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc"
        private const val MIME_TYPE_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC // "audio/mp4a-latm"
        private const val MIME_TYPE = MIME_TYPE_VIDEO // Backward-compat alias
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 2_500_000 // 2.5 Mbps
        private const val I_FRAME_INTERVAL = 1
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 64_000
    }

    private val isRecording = AtomicBoolean(false)
    private var currentOutputFile: File? = null
    private var startTimestampMs = 0L

    private val telemetryFrames = mutableListOf<FrameTelemetry>()
    private val telemetryLock = Any()

    // Background encoding pipeline
    private var encoderThread: Thread? = null
    private val frameQueue = ArrayBlockingQueue<Bitmap>(6)
    private val mainHandler = Handler(Looper.getMainLooper())

    var isVideoSupported: Boolean = true
        private set

    // Kept for backward compatibility with CameraPreviewScreen
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    fun isCurrentlyRecording(): Boolean = isRecording.get()

    fun getRecordingDurationMs(): Long {
        if (!isRecording.get() || startTimestampMs == 0L) return 0L
        return SystemClock.uptimeMillis() - startTimestampMs
    }

    // CameraX VideoCapture compatibility stub: VideoCapture is skipped because
    // AnnotatedVideoRecorder encodes frames with burned-in overlays directly.
    fun createVideoCapture(cameraInfo: CameraInfo? = null): VideoCapture<Recorder>? {
        this.videoCapture = null
        this.isVideoSupported = true
        return null
    }

    fun hasHardwareVideoEncoder(cameraInfo: CameraInfo): Boolean = false

    fun startRecording(
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecording.get()) return

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingsDir = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
            val outputFile = File(recordingsDir, "TRACK_$timeStamp.mp4")
            currentOutputFile = outputFile

            synchronized(telemetryLock) {
                telemetryFrames.clear()
            }
            frameQueue.clear()
            startTimestampMs = SystemClock.uptimeMillis()
            isRecording.set(true)

            encoderThread = Thread({
                runEncodingPipeline(outputFile, onError)
            }, "VisionTrack-Encoder").apply { start() }

            mainHandler.post { onStarted() }
            Log.d(TAG, "Started recording annotated video to: ${outputFile.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            isRecording.set(false)
            mainHandler.post { onError(e.message ?: "Failed to start recording") }
        }
    }

    fun recordFrame(
        frameBitmap: Bitmap,
        detectedObjects: List<DetectedObject>,
        prominentObject: DetectedObject?
    ) {
        if (!isRecording.get()) return

        try {
            // Save telemetry data point for companion metadata
            submitFrameTelemetry(prominentObject)

            // Render camera frame with burned-in detection HUD overlays
            val annotated = renderAnnotatedBitmap(frameBitmap, detectedObjects)

            // Offer to encoding queue without blocking the camera analysis thread
            if (!frameQueue.offer(annotated)) {
                // Drop oldest if encoder falls slightly behind to keep real-time cadence
                frameQueue.poll()
                frameQueue.offer(annotated)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error processing frame for recording: ${e.message}")
        }
    }

    // Legacy method name mapped to recordFrame telemetry
    fun submitFrameTelemetry(prominentObject: DetectedObject?) {
        if (!isRecording.get()) return
        val now = SystemClock.uptimeMillis()
        val elapsedMs = now - startTimestampMs

        if (prominentObject != null) {
            val telemetry = FrameTelemetry(
                timeMs = elapsedMs,
                label = prominentObject.label,
                score = prominentObject.score,
                left = prominentObject.boundingBox.left,
                top = prominentObject.boundingBox.top,
                right = prominentObject.boundingBox.right,
                bottom = prominentObject.boundingBox.bottom,
                velocity = prominentObject.formattedVelocity(),
                trackId = prominentObject.id
            )
            synchronized(telemetryLock) {
                telemetryFrames.add(telemetry)
            }
        }
    }

    fun stopRecording(onComplete: (File?) -> Unit) {
        if (!isRecording.get()) {
            mainHandler.post { onComplete(currentOutputFile) }
            return
        }

        isRecording.set(false)

        Thread {
            try {
                // Wait for encoder thread to flush and finish MP4 muxing cleanly
                encoderThread?.join(4000)
                encoderThread = null

                val file = currentOutputFile
                if (file != null && file.exists() && file.length() > 0) {
                    saveCompanionTelemetry(file)
                    addVideoToMediaStore(file)
                    Log.d(TAG, "Recording finalized successfully: ${file.absolutePath} (${file.length()} bytes)")
                }
                mainHandler.post { onComplete(file) }
            } catch (e: Throwable) {
                Log.e(TAG, "Error finalizing recording: ${e.message}", e)
                mainHandler.post { onComplete(currentOutputFile) }
            }
        }.start()
    }

    /**
     * Renders camera image with burned-in detection bounding boxes, corner brackets,
     * class labels, confidence %, tracking IDs, reticle, and recording status badge.
     */
    private fun renderAnnotatedBitmap(
        source: Bitmap,
        detectedObjects: List<DetectedObject>
    ): Bitmap {
        // Enforce 16-pixel aligned dimensions for H.264 encoder compatibility
        val w = ((source.width / 16) * 16).coerceAtLeast(320)
        val h = ((source.height / 16) * 16).coerceAtLeast(320)

        val annotated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(annotated)

        // 1. Draw base camera image
        val srcRect = Rect(0, 0, source.width, source.height)
        val dstRect = Rect(0, 0, w, h)
        canvas.drawBitmap(source, srcRect, dstRect, null)

        val wf = w.toFloat()
        val hf = h.toFloat()

        // 2. Draw all detected objects
        for (obj in detectedObjects) {
            val left = obj.boundingBox.left * wf
            val top = obj.boundingBox.top * hf
            val right = obj.boundingBox.right * wf
            val bottom = obj.boundingBox.bottom * hf
            val boxW = right - left
            val boxH = bottom - top

            val colorInt = if (obj.isProminent) 0xFFFFB703.toInt() else obj.colorArgb

            // Bounding box
            val boxPaint = Paint().apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = if (obj.isProminent) 5f else 3f
                isAntiAlias = true
            }
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, boxPaint)

            // Tactical Corner Brackets
            val cornerLen = min(boxW, boxH) * 0.22f
            if (cornerLen > 8f) {
                val cornerPaint = Paint().apply {
                    color = colorInt
                    style = Paint.Style.STROKE
                    strokeWidth = if (obj.isProminent) 6f else 4.5f
                    isAntiAlias = true
                }
                // Top-left
                canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
                canvas.drawLine(left, top, left, top + cornerLen, cornerPaint)
                // Top-right
                canvas.drawLine(right, top, right - cornerLen, top, cornerPaint)
                canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)
                // Bottom-left
                canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)
                canvas.drawLine(left, bottom, left, bottom - cornerLen, cornerPaint)
                // Bottom-right
                canvas.drawLine(right, bottom, right - cornerLen, bottom, cornerPaint)
                canvas.drawLine(right, bottom, right, bottom - cornerLen, cornerPaint)
            }

            // Prominent Object Center Crosshair Reticle
            if (obj.isProminent) {
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val reticlePaint = Paint().apply {
                    color = 0xFFFFB703.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    isAntiAlias = true
                }
                canvas.drawCircle(cx, cy, 18f, reticlePaint)
                canvas.drawLine(cx - 24f, cy, cx + 24f, cy, reticlePaint)
                canvas.drawLine(cx, cy - 24f, cx, cy + 24f, reticlePaint)
            }

            // Classification & Confidence Label Badge
            val labelText = if (obj.isProminent) {
                "🎯 ${obj.label.uppercase()} ${(obj.score * 100).toInt()}% #${obj.id}"
            } else {
                "${obj.label} ${(obj.score * 100).toInt()}% #${obj.id}"
            }

            val textPaint = Paint().apply {
                color = AndroidColor.WHITE
                textSize = 22f
                isFakeBoldText = true
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }
            val textWidth = textPaint.measureText(labelText)
            val badgeH = 34f
            val badgeW = textWidth + 18f
            val badgeX = left.coerceIn(8f, (wf - badgeW - 8f).coerceAtLeast(8f))
            val badgeY = if (top - badgeH - 6f < 50f) bottom + 6f else top - badgeH - 6f

            val badgeBg = Paint().apply {
                color = 0xDD080D1A.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawRoundRect(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, 6f, 6f, badgeBg)

            val badgeBorder = Paint().apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            canvas.drawRoundRect(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, 6f, 6f, badgeBorder)
            canvas.drawText(labelText, badgeX + 9f, badgeY + badgeH - 10f, textPaint)
        }

        // 3. Draw Recording Status Badge Watermark in top-left
        val recDotPaint = Paint().apply {
            color = 0xFFFF0055.toInt() // Red REC dot
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(22f, 26f, 6f, recDotPaint)

        val watermarkPaint = Paint().apply {
            color = AndroidColor.WHITE
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val elapsedSec = (SystemClock.uptimeMillis() - startTimestampMs) / 1000
        val timeStr = String.format("REC %02d:%02d • VISIONTRACK", elapsedSec / 60, elapsedSec % 60)
        canvas.drawText(timeStr, 34f, 31f, watermarkPaint)

        return annotated
    }

    /**
     * Dedicated background encoding loop that reads annotated frames from queue,
     * encodes video to H.264 (video/avc) and audio to AAC (audio/mp4a-latm) via MediaCodec,
     * and muxes into an MP4 file with strict container finalization.
     */
    private fun runEncodingPipeline(outputFile: File, onError: (String) -> Unit) {
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var audioRecord: AudioRecord? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormatReady: MediaFormat? = null
        var audioFormatReady: MediaFormat? = null
        var videoSamplesWritten = 0
        var audioSamplesWritten = 0
        var lastVideoPts = 0L
        var lastAudioPts = 0L
        var frameCount = 0L
        var audioSamplesCount = 0L
        var hasAudio = false
        var width = 0
        var height = 0
        var isSemiPlanar = false

        val vBufferInfo = MediaCodec.BufferInfo()
        val aBufferInfo = MediaCodec.BufferInfo()

        // Local function placeholders to handle circular dependencies and scoping
        var pumpAudioSample: () -> Unit = {}
        var drainAudio: (Boolean) -> Unit = {}
        var startMuxerIfReady: () -> Unit = {}
        var drainVideo: (Boolean) -> Unit = {}
        var processSingleFrame: (Bitmap) -> Unit = {}

        pumpAudioSample = {
            val aCodec = audioCodec
            if (aCodec != null) {
                val inIndex = try { aCodec.dequeueInputBuffer(10_000L) } catch (t: Throwable) { -1 }
                if (inIndex >= 0) {
                    val inBuffer = aCodec.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        inBuffer.clear()
                        val pcmBytes = ByteArray(2048)
                        var readBytes = 0
                        val ar = audioRecord
                        if (ar != null && ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val read = ar.read(pcmBytes, 0, pcmBytes.size)
                            if (read > 0) readBytes = read
                        }
                        if (readBytes <= 0) {
                            Arrays.fill(pcmBytes, 0.toByte())
                            readBytes = pcmBytes.size
                        }
                        inBuffer.put(pcmBytes, 0, readBytes)
                        val audioPts = (audioSamplesCount * 1_000_000L) / AUDIO_SAMPLE_RATE
                        aCodec.queueInputBuffer(inIndex, 0, readBytes, audioPts, 0)
                        audioSamplesCount += (readBytes / 2)
                    }
                }
            }
        }

        drainAudio = { endOfStream ->
            val aCodec = audioCodec
            val mx = muxer
            if (aCodec != null && mx != null) {
                var tryCount = 0
                while (true) {
                    val outIndex = try {
                        aCodec.dequeueOutputBuffer(aBufferInfo, if (endOfStream) 10_000L else 0L)
                    } catch (t: Throwable) {
                        break
                    }

                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                        tryCount++
                        if (tryCount >= 8) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioFormatReady = aCodec.outputFormat
                        startMuxerIfReady()
                    } else if (outIndex >= 0) {
                        tryCount = 0
                        if (!muxerStarted && audioFormatReady == null) {
                            audioFormatReady = aCodec.outputFormat
                        }
                        if (muxerStarted && audioTrackIndex >= 0) {
                            val encoded = aCodec.getOutputBuffer(outIndex)
                            if (encoded != null && (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && aBufferInfo.size > 0) {
                                encoded.position(aBufferInfo.offset)
                                encoded.limit(aBufferInfo.offset + aBufferInfo.size)
                                if (aBufferInfo.presentationTimeUs <= lastAudioPts) {
                                    aBufferInfo.presentationTimeUs = lastAudioPts + 1000L
                                }
                                lastAudioPts = aBufferInfo.presentationTimeUs
                                try {
                                    mx.writeSampleData(audioTrackIndex, encoded, aBufferInfo)
                                    audioSamplesWritten++
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Audio sample write: ${e.message}")
                                }
                            }
                        }
                        try { aCodec.releaseOutputBuffer(outIndex, false) } catch (ignored: Throwable) {}
                        if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }

        startMuxerIfReady = {
            val mx = muxer
            if (!muxerStarted && mx != null) {
                val vFmt = videoFormatReady
                // Start if video is ready AND (audio is ready OR we are finishing OR we've waited too long)
                val shouldStartNow = vFmt != null && (!hasAudio || audioFormatReady != null || !isRecording.get() || frameCount > 15)
                
                if (shouldStartNow && vFmt != null) {
                    try {
                        videoTrackIndex = mx.addTrack(vFmt)
                        if (hasAudio && audioFormatReady != null) {
                            audioTrackIndex = mx.addTrack(audioFormatReady!!)
                        } else if (hasAudio) {
                            Log.w(TAG, "Audio format not ready, starting video-only fallback")
                            hasAudio = false // Disable audio for this session
                        }
                        mx.start()
                        muxerStarted = true
                        Log.d(TAG, "MediaMuxer started. VideoIdx=$videoTrackIndex, AudioIdx=$audioTrackIndex")

                        if (audioTrackIndex >= 0) {
                            for (i in 0 until 5) {
                                pumpAudioSample()
                                drainAudio(false)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to start MediaMuxer: ${e.message}")
                    }
                }
            }
        }

        drainVideo = { endOfStream ->
            val codec = videoCodec
            val mx = muxer
            if (codec != null && mx != null) {
                var tryCount = 0
                while (true) {
                    val outIndex = try {
                        codec.dequeueOutputBuffer(vBufferInfo, if (endOfStream) 10_000L else 0L)
                    } catch (t: Throwable) {
                        break
                    }

                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                        tryCount++
                        if (tryCount >= 10) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoFormatReady = codec.outputFormat
                        startMuxerIfReady()
                    } else if (outIndex >= 0) {
                        tryCount = 0
                        if (!muxerStarted) {
                            if (videoFormatReady == null) {
                                videoFormatReady = codec.outputFormat
                            }
                            startMuxerIfReady()
                        }
                        if (muxerStarted && videoTrackIndex >= 0) {
                            val encoded = codec.getOutputBuffer(outIndex)
                            if (encoded != null && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && vBufferInfo.size > 0) {
                                encoded.position(vBufferInfo.offset)
                                encoded.limit(vBufferInfo.offset + vBufferInfo.size)
                                if (vBufferInfo.presentationTimeUs <= lastVideoPts) {
                                    vBufferInfo.presentationTimeUs = lastVideoPts + 1000L
                                }
                                lastVideoPts = vBufferInfo.presentationTimeUs
                                try {
                                    mx.writeSampleData(videoTrackIndex, encoded, vBufferInfo)
                                    videoSamplesWritten++
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Video sample write: ${e.message}")
                                }
                            }
                        }
                        try { codec.releaseOutputBuffer(outIndex, false) } catch (ignored: Throwable) {}
                        if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }

        processSingleFrame = { frame ->
            val codec = videoCodec
            if (codec != null) {
                val inputIndex = try {
                    codec.dequeueInputBuffer(10_000L)
                } catch (t: Throwable) {
                    -1
                }

                if (inputIndex >= 0) {
                    try {
                        val inputImage = try { codec.getInputImage(inputIndex) } catch (t: Throwable) { null }
                        val inputBuffer = try { codec.getInputBuffer(inputIndex) } catch (t: Throwable) { null }
                        val presentationTimeUs = (frameCount * 1_000_000L) / FRAME_RATE

                        val bufferCap = inputBuffer?.capacity()
                            ?: inputImage?.planes?.getOrNull(0)?.buffer?.capacity()
                            ?: (width * height * 3 / 2)

                        if (inputImage != null) {
                            writeBitmapToImage(frame, inputImage)
                            val yStride = inputImage.planes[0].rowStride
                            val expectedBytes = yStride * height * 3 / 2
                            val dataSize = expectedBytes.coerceAtMost(bufferCap)
                            codec.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
                            frameCount++
                        } else if (inputBuffer != null) {
                            inputBuffer.clear()
                            writeBitmapToByteBuffer(frame, inputBuffer, width, height, isSemiPlanar)
                            val dataSize = (width * height * 3 / 2).coerceAtMost(bufferCap)
                            codec.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
                            frameCount++
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Frame queue error: ${t.message}")
                    } finally {
                        frame.recycle()
                    }
                } else {
                    frame.recycle()
                }

                drainVideo(false)
                if (hasAudio) {
                    pumpAudioSample()
                    drainAudio(false)
                }
            }
        }

        try {
            // Wait for first frame to determine exact resolution
            var firstFrame: Bitmap? = null
            val waitTimeoutMs = SystemClock.uptimeMillis() + 4000
            while (isRecording.get() && firstFrame == null && SystemClock.uptimeMillis() < waitTimeoutMs) {
                firstFrame = frameQueue.poll(100, TimeUnit.MILLISECONDS)
            }
            if (firstFrame == null) {
                Log.w(TAG, "No frames received during recording window")
                return
            }

            width = firstFrame.width
            height = firstFrame.height

            // 1. Initialize Video Encoder (video/avc)
            videoCodec = MediaCodec.createEncoderByType(MIME_TYPE_VIDEO)
            val caps = videoCodec.codecInfo.getCapabilitiesForType(MIME_TYPE_VIDEO)
            val supportedFormats = caps.colorFormats
            val colorFormat = when {
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
            isSemiPlanar = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

            val vFormat = MediaFormat.createVideoFormat(MIME_TYPE_VIDEO, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

                try {
                    val encoderCaps = caps.encoderCapabilities
                    if (encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    } else if (encoderCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    }
                } catch (ignored: Throwable) {}

                try {
                    val profileLevels = caps.profileLevels
                    val hasBaseline = profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                    if (hasBaseline) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                    }
                } catch (ignored: Throwable) {}
            }

            videoCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoCodec.start()

            // 2. Initialize Audio Encoder (audio/mp4a-latm) and Audio Source
            hasAudio = false
            try {
                val aFormat = MediaFormat.createAudioFormat(MIME_TYPE_AUDIO, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                audioCodec = MediaCodec.createEncoderByType(MIME_TYPE_AUDIO)
                audioCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec.start()
                hasAudio = true

                val minBufSize = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val recBufSize = maxOf(minBufSize, 4096)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        val ar = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            AUDIO_SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            recBufSize
                        )
                        if (ar.state == AudioRecord.STATE_INITIALIZED) {
                            ar.startRecording()
                            audioRecord = ar
                        } else {
                            ar.release()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "AudioRecord start note: ${t.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Audio encoding unavailable, continuing with video-only: ${e.message}")
                hasAudio = false
                audioCodec?.release()
                audioCodec = null
            }

            // 3. Initialize MP4 Muxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Prime audio encoder to generate audio format immediately
            if (hasAudio) {
                pumpAudioSample()
                drainAudio(false)
            }

            // Process first frame
            processSingleFrame(firstFrame)

            // Loop while recording is ongoing
            while (isRecording.get()) {
                val nextFrame = frameQueue.poll(30, TimeUnit.MILLISECONDS)
                if (nextFrame != null) {
                    processSingleFrame(nextFrame)
                } else if (hasAudio) {
                    pumpAudioSample()
                    drainAudio(false)
                }
            }

            // Drain any pending frames queued right before recording stopped
            while (true) {
                val extraFrame = frameQueue.poll() ?: break
                processSingleFrame(extraFrame)
            }

            // Signal End-of-Stream to video encoder
            var vEosQueued = false
            val eosDeadline = SystemClock.uptimeMillis() + 1500
            while (!vEosQueued && SystemClock.uptimeMillis() < eosDeadline) {
                val eosInputIndex = try { videoCodec.dequeueInputBuffer(10_000L) } catch (t: Throwable) { -1 }
                if (eosInputIndex >= 0) {
                    val pts = (frameCount * 1_000_000L) / FRAME_RATE
                    videoCodec.queueInputBuffer(eosInputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    vEosQueued = true
                } else {
                    drainVideo(false)
                }
            }

            // Signal End-of-Stream to audio encoder
            if (hasAudio && audioCodec != null) {
                var aEosQueued = false
                val aEosDeadline = SystemClock.uptimeMillis() + 1500
                while (!aEosQueued && SystemClock.uptimeMillis() < aEosDeadline) {
                    val aInIdx = try { audioCodec.dequeueInputBuffer(10_000L) } catch (t: Throwable) { -1 }
                    if (aInIdx >= 0) {
                        val aPts = (audioSamplesCount * 1_000_000L) / AUDIO_SAMPLE_RATE
                        audioCodec.queueInputBuffer(aInIdx, 0, 0, aPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        aEosQueued = true
                    } else {
                        drainAudio(false)
                    }
                }
            }

            // Drain remaining output buffers until EOS flag received
            drainVideo(true)
            if (hasAudio) {
                drainAudio(true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Encoding pipeline error: ${e.message}", e)
            isRecording.set(false)
            mainHandler.post { onError(e.message ?: "Encoding error") }
        } finally {
            isRecording.set(false)
            
            // Final drain and dummy pump to satisfy MPEG4Writer
            try {
                drainVideo(true)
                if (hasAudio) drainAudio(true)
            } catch (ignored: Throwable) {}

            try {
                val mx = muxer
                if (mx != null) {
                    if (muxerStarted) {
                        // Ensure both tracks have at least one sample
                        if (audioTrackIndex >= 0 && audioSamplesWritten == 0) {
                            Log.w(TAG, "Forcing dummy audio in finally")
                            for (i in 0 until 10) {
                                pumpAudioSample()
                                drainAudio(false)
                            }
                        }
                        
                        val canStop = (videoSamplesWritten > 0) && (audioTrackIndex < 0 || audioSamplesWritten > 0)
                        if (canStop) {
                            mx.stop()
                            Log.d(TAG, "MediaMuxer stopped successfully.")
                        } else {
                            Log.w(TAG, "Muxer cannot stop: V=$videoSamplesWritten, A=$audioSamplesWritten")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping muxer in finally: ${e.message}")
            }

            try { muxer?.release() } catch (ignored: Throwable) {}
            try { videoCodec?.stop() } catch (ignored: Throwable) {}
            try { videoCodec?.release() } catch (ignored: Throwable) {}
            try { audioCodec?.stop() } catch (ignored: Throwable) {}
            try { audioCodec?.release() } catch (ignored: Throwable) {}
            try { audioRecord?.stop() } catch (ignored: Throwable) {}
            try { audioRecord?.release() } catch (ignored: Throwable) {}

            // Clear and recycle any residual queue bitmaps
            while (true) {
                val residual = frameQueue.poll() ?: break
                try { residual.recycle() } catch (ignored: Throwable) {}
            }
        }
    }

    /**
     * Stride-aware hardware/software image filler that adapts dynamically to
     * exact rowStride, pixelStride, NV12, or I420 plane layouts provided by Android.
     * Prevents all stride-shift skew and color corruption.
     */
    private fun writeBitmapToImage(bitmap: Bitmap, image: Image) {
        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)

        if (bitmap.width == width && bitmap.height == height) {
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            scaled.getPixels(argb, 0, width, 0, 0, width, height)
            if (scaled != bitmap) scaled.recycle()
        }

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val yCap = yBuffer.capacity()
        val uCap = uBuffer.capacity()
        val vCap = vBuffer.capacity()

        // 1. Write Y luminance plane
        for (row in 0 until height) {
            val rowStart = row * width
            val yRowStart = row * yRowStride
            for (col in 0 until width) {
                val c = argb[rowStart + col]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val idx = yRowStart + col * yPixelStride
                if (idx < yCap) {
                    yBuffer.put(idx, (if (y < 16) 16 else if (y > 235) 235 else y).toByte())
                }
            }
        }

        // 2. Write U (Cb) and V (Cr) chrominance planes (2x2 subsampling)
        val chromaH = height / 2
        val chromaW = width / 2

        for (chRow in 0 until chromaH) {
            val srcRow = chRow * 2
            val rowStart = srcRow * width
            val uRowStart = chRow * uRowStride
            val vRowStart = chRow * vRowStride

            for (chCol in 0 until chromaW) {
                val srcCol = chCol * 2
                val c = argb[rowStart + srcCol]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                val uByte = (if (u < 16) 16 else if (u > 240) 240 else u).toByte()
                val vByte = (if (v < 16) 16 else if (v > 240) 240 else v).toByte()

                val uIdx = uRowStart + chCol * uPixelStride
                val vIdx = vRowStart + chCol * vPixelStride
                if (uIdx < uCap) {
                    uBuffer.put(uIdx, uByte)
                }
                if (vIdx < vCap) {
                    vBuffer.put(vIdx, vByte)
                }
            }
        }
    }

    /**
     * Fallback byte buffer writer for devices without Image mapping.
     * Pre-fills chroma region with 128 (neutral gray) to guarantee NO GREEN NIGHT VISION.
     */
    private fun writeBitmapToByteBuffer(
        bitmap: Bitmap,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        isSemiPlanar: Boolean
    ) {
        val frameSize = width * height
        val yuvSize = frameSize * 3 / 2
        val yuv = ByteArray(yuvSize)

        // Pre-fill chroma plane with neutral 128 to completely prevent green screen
        Arrays.fill(yuv, frameSize, yuvSize, 128.toByte())

        val argb = IntArray(frameSize)
        if (bitmap.width == width && bitmap.height == height) {
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            scaled.getPixels(argb, 0, width, 0, 0, width, height)
            if (scaled != bitmap) scaled.recycle()
        }

        var yIndex = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = (if (y < 16) 16 else if (y > 235) 235 else y).toByte()
            }
        }

        if (isSemiPlanar) {
            // NV12: interleaved U, V
            var uvIndex = frameSize
            for (j in 0 until height step 2) {
                for (i in 0 until width step 2) {
                    val c = argb[j * width + i]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = (if (u < 16) 16 else if (u > 240) 240 else u).toByte()
                    yuv[uvIndex++] = (if (v < 16) 16 else if (v > 240) 240 else v).toByte()
                }
            }
        } else {
            // I420: planar U then V
            var uIndex = frameSize
            var vIndex = frameSize + (frameSize / 4)
            for (j in 0 until height step 2) {
                for (i in 0 until width step 2) {
                    val c = argb[j * width + i]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uIndex++] = (if (u < 16) 16 else if (u > 240) 240 else u).toByte()
                    yuv[vIndex++] = (if (v < 16) 16 else if (v > 240) 240 else v).toByte()
                }
            }
        }

        buffer.put(yuv)
    }

    private fun saveCompanionTelemetry(videoFile: File) {
        try {
            val metaFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.meta")
            val jsonArray = JSONArray()
            synchronized(telemetryLock) {
                telemetryFrames.forEach { frame ->
                    val obj = JSONObject().apply {
                        put("t", frame.timeMs)
                        put("l", frame.label)
                        put("s", frame.score.toDouble())
                        put("x1", frame.left.toDouble())
                        put("y1", frame.top.toDouble())
                        put("x2", frame.right.toDouble())
                        put("y2", frame.bottom.toDouble())
                        put("v", frame.velocity)
                        put("id", frame.trackId)
                    }
                    jsonArray.put(obj)
                }
            }
            metaFile.writeText(jsonArray.toString())
            Log.d(TAG, "Saved companion telemetry with ${telemetryFrames.size} frames to ${metaFile.name}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save companion telemetry: ${e.message}")
        }
    }

    private fun addVideoToMediaStore(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return

            val now = System.currentTimeMillis() / 1000L
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, now)
                put(MediaStore.Video.Media.DATE_MODIFIED, now)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VisionTrack")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outStream ->
                    file.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not insert into MediaStore: ${e.message}")
        }
    }
}
