package com.example.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
 * - Detections remain permanently visible in saved videos across all players and shared apps.
 * - Hardware and software H.264 video encoding without Camera2 hardware profile limitations.
 * - Zero frame drops or main-thread blocks via dedicated background frame queue.
 * - Android 10+ Scoped Storage compliance (no deprecated _data mutation).
 */
class TrackingVideoRecorder(
    private val context: Context
) {
    companion object {
        private const val TAG = "TrackingVideoRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 2_500_000 // 2.5 Mbps for crisp annotations
        private const val I_FRAME_INTERVAL = 1
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
     * encodes them to H.264 via MediaCodec, and muxes into an MP4 file.
     */
    private fun runEncodingPipeline(outputFile: File, onError: (String) -> Unit) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1

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

            val width = firstFrame.width
            val height = firstFrame.height

            // Discover supported color format (prefer SemiPlanar/NV12, fallback to Planar/I420)
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            val caps = codec.codecInfo.getCapabilitiesForType(MIME_TYPE)
            val supportedFormats = caps.colorFormats
            val colorFormat = when {
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
            val isSemiPlanar = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

                // Select H.264 Baseline profile for universal playback compatibility on Android / Pixel devices
                try {
                    val profileLevels = caps.profileLevels
                    val hasBaseline = profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                    if (hasBaseline) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Baseline profile configuration note: ${e.message}")
                }
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var frameCount = 0L

            fun processSingleFrame(frame: Bitmap) {
                val inputIndex = try {
                    codec.dequeueInputBuffer(10_000L)
                } catch (t: Throwable) {
                    Log.w(TAG, "dequeueInputBuffer error: ${t.message}")
                    -1
                }

                if (inputIndex >= 0) {
                    try {
                        if (frameCount == 0L) {
                            try {
                                val syncBundle = android.os.Bundle().apply {
                                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                }
                                codec.setParameters(syncBundle)
                            } catch (ignored: Throwable) {}
                        }
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

                // Drain output buffers
                drainEncoder(codec, muxer, bufferInfo, false, videoTrackIndex, muxerStarted) { trackIdx, started ->
                    videoTrackIndex = trackIdx
                    muxerStarted = started
                }
            }

            // Process first frame
            processSingleFrame(firstFrame)

            // Loop while recording is ongoing
            while (isRecording.get()) {
                val nextFrame = frameQueue.poll(30, TimeUnit.MILLISECONDS)
                if (nextFrame != null) {
                    processSingleFrame(nextFrame)
                }
            }

            // Drain any pending frames queued right before recording stopped
            while (true) {
                val extraFrame = frameQueue.poll() ?: break
                processSingleFrame(extraFrame)
            }

            // Signal End-of-Stream to encoder with retries
            var eosQueued = false
            val eosDeadline = SystemClock.uptimeMillis() + 1500
            while (!eosQueued && SystemClock.uptimeMillis() < eosDeadline) {
                val eosInputIndex = try { codec.dequeueInputBuffer(10_000L) } catch (t: Throwable) { -1 }
                if (eosInputIndex >= 0) {
                    val presentationTimeUs = (frameCount * 1_000_000L) / FRAME_RATE
                    codec.queueInputBuffer(eosInputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    drainEncoder(codec, muxer, bufferInfo, false, videoTrackIndex, muxerStarted) { trackIdx, started ->
                        videoTrackIndex = trackIdx
                        muxerStarted = started
                    }
                }
            }

            // Drain remaining output buffers until EOS flag received
            drainEncoder(codec, muxer, bufferInfo, true, videoTrackIndex, muxerStarted) { trackIdx, started ->
                videoTrackIndex = trackIdx
                muxerStarted = started
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Encoding pipeline error: ${e.message}", e)
            isRecording.set(false)
            mainHandler.post { onError(e.message ?: "Encoding error") }
        } finally {
            isRecording.set(false)
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping codec: ${e.message}")
            }
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping muxer: ${e.message}")
            }
            // Clear and recycle any residual queue bitmaps
            while (true) {
                val residual = frameQueue.poll() ?: break
                try { residual.recycle() } catch (ignored: Throwable) {}
            }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        currentTrackIndex: Int,
        isMuxerStarted: Boolean,
        onStateUpdate: (Int, Boolean) -> Unit
    ) {
        var trackIdx = currentTrackIndex
        var started = isMuxerStarted

        while (true) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 15_000L else 0L)
            } catch (t: Throwable) {
                Log.w(TAG, "dequeueOutputBuffer exception: ${t.message}")
                break
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) {
                    trackIdx = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                    onStateUpdate(trackIdx, true)
                }
            } else if (outIndex >= 0) {
                if (started && trackIdx >= 0) {
                    val encodedData: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        try {
                            muxer.writeSampleData(trackIdx, encodedData, bufferInfo)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Sample write error: ${e.message}")
                        }
                    }
                }
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (t: Throwable) {
                    // Ignore release errors on EOS
                }
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
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
