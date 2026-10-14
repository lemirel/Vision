package com.example.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.DetectedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TrackingVideoRecorder(
    private val context: Context
) {
    companion object {
        private const val TAG = "TrackingVideoRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 25
        private const val I_FRAME_INTERVAL = 1
        private const val BIT_RATE = 3_500_000
    }

    private val isRecording = AtomicBoolean(false)
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var outputFile: File? = null
    private var videoWidth = 720
    private var videoHeight = 1280
    private var frameCount = 0L
    private var startTimestampMs = 0L
    private var lastRecordedFrameTimeMs = 0L

    private var encodingJob: Job? = null
    private val frameChannel = Channel<FrameTask>(capacity = 4)

    private val bufferInfo = MediaCodec.BufferInfo()

    // Paints for overlay
    private val bracketPaint = Paint().apply {
        color = 0xFFFFB703.toInt() // Target Amber
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val glowPaint = Paint().apply {
        color = 0x55FFB703.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 14f
        isAntiAlias = true
    }

    private val trailPaint = Paint().apply {
        color = 0xFF00F5D4.toInt() // Cyber Cyan
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val trailPointPaint = Paint().apply {
        color = 0xFF70FFF0.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBgPaint = Paint().apply {
        color = 0xCC080D1A.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudBorderPaint = Paint().apply {
        color = 0xFFFFB703.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val textSubPaint = Paint().apply {
        color = 0xFFFFB703.toInt()
        textSize = 22f
        isFakeBoldText = false
        isAntiAlias = true
    }

    private val watermarkPaint = Paint().apply {
        color = 0xCCF1F5F9.toInt()
        textSize = 20f
        isAntiAlias = true
    }

    private val recDotPaint = Paint().apply {
        color = 0xFFFF3366.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private data class FrameTask(
        val bitmap: Bitmap,
        val prominentObject: DetectedObject?,
        val allObjectsCount: Int,
        val timestampMs: Long
    )

    fun isCurrentlyRecording(): Boolean = isRecording.get()

    fun getRecordingDurationMs(): Long {
        if (!isRecording.get() || startTimestampMs == 0L) return 0L
        return SystemClock.uptimeMillis() - startTimestampMs
    }

    suspend fun startRecording(width: Int = 720, height: Int = 1280): File? = withContext(Dispatchers.IO) {
        if (isRecording.get()) return@withContext outputFile

        try {
            // Even dimensions required for AVC
            videoWidth = if (width % 2 == 0) width else width - 1
            videoHeight = if (height % 2 == 0) height else height - 1

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingsDir = File(context.filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val file = File(recordingsDir, "TRACK_$timeStamp.mp4")
            outputFile = file

            val format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            trackIndex = -1
            frameCount = 0L
            startTimestampMs = SystemClock.uptimeMillis()
            lastRecordedFrameTimeMs = 0L
            isRecording.set(true)

            // Start background encoding loop
            encodingJob = CoroutineScope(Dispatchers.Default).launch {
                encodeLoop()
            }

            Log.d(TAG, "Tracking video recording started: ${file.absolutePath} ($videoWidth x $videoHeight)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            null
        }
    }

    fun submitFrame(bitmap: Bitmap, prominentObject: DetectedObject?, allObjectsCount: Int) {
        if (!isRecording.get()) return
        val now = SystemClock.uptimeMillis()
        // Limit to ~25-30 fps
        if (now - lastRecordedFrameTimeMs < 35) return
        lastRecordedFrameTimeMs = now

        // Create a copy of the bitmap for background processing
        try {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val task = FrameTask(copy, prominentObject, allObjectsCount, now)
            frameChannel.trySend(task)
        } catch (e: Exception) {
            Log.e(TAG, "submitFrame error: ${e.message}")
        }
    }

    private suspend fun encodeLoop() {
        val yuvBuffer = ByteArray(videoWidth * videoHeight * 3 / 2)
        var canvasBitmap: Bitmap? = null
        var canvas: Canvas? = null

        while (isRecording.get() || !frameChannel.isEmpty) {
            val task = frameChannel.receiveCatching().getOrNull() ?: break

            try {
                if (canvasBitmap == null || canvasBitmap.width != videoWidth || canvasBitmap.height != videoHeight) {
                    canvasBitmap?.recycle()
                    canvasBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                    canvas = Canvas(canvasBitmap)
                }

                val currentCanvas = canvas!!
                val currentBitmap = canvasBitmap!!

                // 1. Draw camera frame scaled to output size
                currentCanvas.drawBitmap(
                    task.bitmap,
                    null,
                    RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat()),
                    null
                )
                task.bitmap.recycle()

                // 2. Render detection and tracking process overlay
                renderTrackingOverlay(currentCanvas, task.prominentObject, task.allObjectsCount, task.timestampMs)

                // 3. Convert ARGB to NV12 (YUV420SemiPlanar)
                encodeYUV420SP(yuvBuffer, currentBitmap, videoWidth, videoHeight)

                // 4. Feed to MediaCodec
                feedEncoder(yuvBuffer, false)
            } catch (e: Exception) {
                Log.e(TAG, "Error during frame encoding: ${e.message}", e)
            }
        }

        canvasBitmap?.recycle()
    }

    private fun renderTrackingOverlay(
        canvas: Canvas,
        prominent: DetectedObject?,
        allObjectsCount: Int,
        timestampMs: Long
    ) {
        val w = videoWidth.toFloat()
        val h = videoHeight.toFloat()

        // 1. Prominent Object Highlight & Reticle
        if (prominent != null) {
            val box = prominent.boundingBox
            val left = box.left * w
            val top = box.top * h
            val right = box.right * w
            val bottom = box.bottom * h
            val boxW = right - left
            val boxH = bottom - top

            // Trajectory path (cyan glowing breadcrumb trail)
            if (prominent.trajectory.size > 1) {
                val path = Path()
                prominent.trajectory.forEachIndexed { i, pt ->
                    val px = pt.x * w
                    val py = pt.y * h
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    canvas.drawCircle(px, py, 3f + (i / 4f), trailPointPaint)
                }
                canvas.drawPath(path, trailPaint)
            }

            // Glow around prominent object
            canvas.drawRect(left - 4, top - 4, right + 4, bottom + 4, glowPaint)

            // High-tech corner target brackets
            val bracketLen = minOf(boxW, boxH) * 0.28f
            // Top-left
            canvas.drawLine(left, top, left + bracketLen, top, bracketPaint)
            canvas.drawLine(left, top, left, top + bracketLen, bracketPaint)
            // Top-right
            canvas.drawLine(right, top, right - bracketLen, top, bracketPaint)
            canvas.drawLine(right, top, right, top + bracketLen, bracketPaint)
            // Bottom-left
            canvas.drawLine(left, bottom, left + bracketLen, bottom, bracketPaint)
            canvas.drawLine(left, bottom, left, bottom - bracketLen, bracketPaint)
            // Bottom-right
            canvas.drawLine(right, bottom, right - bracketLen, bottom, bracketPaint)
            canvas.drawLine(right, bottom, right, bottom - bracketLen, bracketPaint)

            // Center target crosshair
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            canvas.drawLine(cx - 16f, cy, cx + 16f, cy, bracketPaint)
            canvas.drawLine(cx, cy - 16f, cx, cy + 16f, bracketPaint)
            canvas.drawCircle(cx, cy, 18f, bracketPaint)

            // Target Telemetry HUD Box
            val hudW = 280f
            val hudH = 80f
            val hudLeft = left.coerceIn(16f, w - hudW - 16f)
            val hudTop = (top - hudH - 12f).let { if (it < 90f) bottom + 12f else it }
            val hudRect = RectF(hudLeft, hudTop, hudLeft + hudW, hudTop + hudH)

            canvas.drawRoundRect(hudRect, 10f, 10f, hudBgPaint)
            canvas.drawRoundRect(hudRect, 10f, 10f, hudBorderPaint)

            val trackIdStr = if (prominent.isTrackedAcrossFrames) "TRACK ID #${prominent.id}" else "ID #${prominent.id}"
            canvas.drawText("🎯 LOCKED: ${prominent.label.uppercase()} [$trackIdStr]", hudLeft + 14f, hudTop + 32f, textPaint)
            canvas.drawText("Conf: ${prominent.formattedScore()}  •  ${prominent.formattedVelocity()}", hudLeft + 14f, hudTop + 62f, textSubPaint)
        }

        // 2. Top-Left REC status & timer
        val elapsedSec = ((timestampMs - startTimestampMs) / 1000).toInt()
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)

        // Blinking REC dot every 500ms
        val isBlinkOn = (timestampMs / 500) % 2 == 0L
        if (isBlinkOn) {
            canvas.drawCircle(36f, 50f, 12f, recDotPaint)
        }
        textPaint.color = Color.WHITE
        canvas.drawText("REC $timeString", 58f, 58f, textPaint)

        // 3. Bottom HUD Banner
        val bannerH = 50f
        val bannerRect = RectF(0f, h - bannerH, w, h)
        canvas.drawRect(bannerRect, hudBgPaint)
        canvas.drawText("VISIONTRACK AI • PROMINENT OBJECT TRACKING • MEDIAPIPE", 20f, h - 18f, watermarkPaint)
    }

    private fun feedEncoder(yuv: ByteArray, endOfStream: Boolean) {
        val codec = encoder ?: return

        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()
            if (!endOfStream) {
                inputBuffer?.put(yuv)
                val pts = frameCount * (1_000_000L / FRAME_RATE)
                codec.queueInputBuffer(inputBufferIndex, 0, yuv.size, pts, 0)
                frameCount++
            } else {
                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }

        drainEncoder(false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val currentMuxer = muxer ?: return

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw IllegalStateException("Format changed twice")
                }
                val newFormat = codec.outputFormat
                trackIndex = currentMuxer.addTrack(newFormat)
                currentMuxer.start()
                muxerStarted = true
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex)
                if (encodedData != null) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        currentMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, bitmap: Bitmap, width: Int, height: Int) {
        val frameSize = width * height
        val argb = IntArray(frameSize)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = frameSize

        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index++]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                // RGB to YUV formula
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv420sp[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv420sp[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv420sp[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        if (!isRecording.get()) return@withContext outputFile
        isRecording.set(false)

        try {
            // Signal end of stream
            feedEncoder(ByteArray(0), true)
            drainEncoder(true)
            encodingJob?.cancel()

            cleanup()
            val file = outputFile

            // Insert into MediaStore for gallery accessibility
            if (file != null && file.exists() && file.length() > 0) {
                addVideoToMediaStore(file)
            }

            Log.d(TAG, "Recording saved successfully: ${file?.absolutePath} (${file?.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
            cleanup()
            outputFile
        }
    }

    private fun addVideoToMediaStore(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, file.nameWithoutExtension)
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATA, file.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VisionTrack")
                }
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "Could not insert into MediaStore: ${e.message}")
        }
    }

    private fun cleanup() {
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Encoder cleanup error: ${e.message}")
        }
        encoder = null

        try {
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Muxer cleanup error: ${e.message}")
        }
        muxer = null
        muxerStarted = false
    }
}
