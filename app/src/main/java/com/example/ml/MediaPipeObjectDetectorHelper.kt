package com.example.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.example.data.model.DetectedObject
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface DetectorStatus {
    data object Initializing : DetectorStatus
    data object Ready : DetectorStatus
    data class Error(val message: String) : DetectorStatus
}

/**
 * Core Object Detector configuration and initialization service using
 * MediaPipe Vision Tasks.
 *
 * Configures and manages:
 * - Real-time continuous object detection in LIVE_STREAM mode
 * - Single static image detection in IMAGE mode (e.g. for gallery analysis)
 * - Dynamic sensitivity adjustments (score threshold, max results, CPU/GPU delegate)
 * - Monotonically increasing frame timestamp enforcement
 * - Automatic hardware acceleration fallback (GPU -> CPU)
 */
class MediaPipeObjectDetectorHelper(
    private val context: Context,
    var threshold: Float = 0.45f,
    var maxResults: Int = 6,
    var useGpu: Boolean = false,
    val onLiveDetectionResult: ((List<DetectedObject>, Long, Long) -> Unit)? = null,
    val onError: ((String) -> Unit)? = null,
    val onStatusChanged: ((DetectorStatus) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MediaPipeDetector"
        const val MODEL_PATH = "efficientdet_lite0.tflite"
    }

    private var liveStreamDetector: ObjectDetector? = null
    private var staticImageDetector: ObjectDetector? = null
    private val isClosed = AtomicBoolean(false)
    private val lastLiveTimestampMs = AtomicLong(0L)

    var currentStatus: DetectorStatus = DetectorStatus.Initializing
        private set

    init {
        // Only spin up live stream detector if a live detection listener is provided
        if (onLiveDetectionResult != null) {
            setupLiveStreamDetector()
        } else {
            currentStatus = DetectorStatus.Ready
        }
    }

    @Synchronized
    fun setupLiveStreamDetector() {
        if (isClosed.get()) return

        updateStatus(DetectorStatus.Initializing)
        try {
            liveStreamDetector?.close()
            liveStreamDetector = null

            // Verify model asset is present
            try {
                context.assets.open(MODEL_PATH).close()
            } catch (e: Throwable) {
                val errorMsg = "Model asset '$MODEL_PATH' not found in assets."
                Log.e(TAG, errorMsg, e)
                updateStatus(DetectorStatus.Error(errorMsg))
                onError?.invoke(errorMsg)
                return
            }

            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)

            if (useGpu) {
                baseOptionsBuilder.setDelegate(Delegate.GPU)
            } else {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
            }

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: ObjectDetectionResult, inputImage: MPImage ->
                    if (isClosed.get()) return@setResultListener
                    val inferenceTime = (SystemClock.uptimeMillis() - result.timestampMs()).coerceAtLeast(0L)
                    val detectedObjects = convertResult(result, inputImage.width, inputImage.height)
                    onLiveDetectionResult?.invoke(detectedObjects, inferenceTime, result.timestampMs())
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe LiveStream error: ${error.message}", error)
                    val msg = error.message ?: "MediaPipe detection error"
                    onError?.invoke(msg)
                    updateStatus(DetectorStatus.Error(msg))
                }
                .build()

            liveStreamDetector = ObjectDetector.createFromOptions(context, options)
            updateStatus(DetectorStatus.Ready)
            Log.d(TAG, "MediaPipe LiveStream ObjectDetector initialized successfully (GPU=$useGpu)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize LiveStream ObjectDetector (GPU=$useGpu): ${e.message}", e)
            if (useGpu) {
                // Graceful fallback to CPU delegate
                Log.i(TAG, "Retrying initialization with CPU delegate...")
                useGpu = false
                setupLiveStreamDetector()
            } else {
                val errorMsg = "Could not initialize MediaPipe detector: ${e.localizedMessage ?: e.message}"
                updateStatus(DetectorStatus.Error(errorMsg))
                onError?.invoke(errorMsg)
            }
        }
    }

    private fun updateStatus(status: DetectorStatus) {
        currentStatus = status
        onStatusChanged?.invoke(status)
    }

    @Synchronized
    private fun getOrCreateStaticDetector(): ObjectDetector? {
        if (isClosed.get()) return null
        if (staticImageDetector != null) return staticImageDetector

        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.CPU)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults.coerceAtLeast(10))
                .setRunningMode(RunningMode.IMAGE)
                .build()

            staticImageDetector = ObjectDetector.createFromOptions(context, options)
            staticImageDetector
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Static ObjectDetector: ${e.message}", e)
            onError?.invoke("Error creating static detector: ${e.localizedMessage}")
            null
        }
    }

    /**
     * Feeds a single live camera frame to the MediaPipe LIVE_STREAM Object Detector.
     * Enforces strictly monotonically increasing timestamps required by MediaPipe.
     */
    @Synchronized
    fun detectLiveStream(bitmap: Bitmap, timestampMs: Long) {
        if (isClosed.get()) return
        val detector = liveStreamDetector ?: return

        try {
            // Guarantee strictly monotonically increasing timestamp
            var previous = lastLiveTimestampMs.get()
            var validTimestamp = if (timestampMs <= previous) previous + 1 else timestampMs
            while (!lastLiveTimestampMs.compareAndSet(previous, validTimestamp)) {
                previous = lastLiveTimestampMs.get()
                validTimestamp = if (timestampMs <= previous) previous + 1 else timestampMs
            }

            // MediaPipe requires ARGB_8888 bitmap format
            val safeBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }

            val mpImage = BitmapImageBuilder(safeBitmap).build()
            detector.detectAsync(mpImage, validTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "detectLiveStream failed: ${e.message}", e)
        }
    }

    /**
     * Synchronously detects objects in a static image (e.g. from device gallery).
     */
    fun detectImage(bitmap: Bitmap): List<DetectedObject> {
        if (isClosed.get()) return emptyList()
        val detector = getOrCreateStaticDetector() ?: return emptyList()
        return try {
            val safeBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            val mpImage = BitmapImageBuilder(safeBitmap).build()
            val result = detector.detect(mpImage)
            convertResult(result, safeBitmap.width, safeBitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "detectImage failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun convertResult(
        result: ObjectDetectionResult,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedObject> {
        val w = imageWidth.toFloat().coerceAtLeast(1f)
        val h = imageHeight.toFloat().coerceAtLeast(1f)

        return result.detections().mapIndexed { index, detection ->
            val category = detection.categories().firstOrNull()
            val label = category?.categoryName() ?: "Object"
            val score = category?.score() ?: 0f

            val rawBox = detection.boundingBox()
            val normalizedBox = RectF(
                (rawBox.left / w).coerceIn(0f, 1f),
                (rawBox.top / h).coerceIn(0f, 1f),
                (rawBox.right / w).coerceIn(0f, 1f),
                (rawBox.bottom / h).coerceIn(0f, 1f)
            )

            DetectedObject(
                id = index + 1,
                label = label.replaceFirstChar { it.uppercase() },
                score = score,
                boundingBox = normalizedBox,
                colorArgb = generateColorForIndex(index)
            )
        }
    }

    private fun generateColorForIndex(index: Int): Int {
        val colors = intArrayOf(
            0xFF00F5D4.toInt(), // Cyan
            0xFFFFB703.toInt(), // Amber
            0xFF06D6A0.toInt(), // Green
            0xFF118AB2.toInt(), // Blue
            0xFFFF5400.toInt(), // Orange
            0xFF9D4EDD.toInt(), // Purple
            0xFFFF006E.toInt()  // Pink
        )
        return colors[index % colors.size]
    }

    fun updateSettings(newThreshold: Float, newMaxResults: Int, newUseGpu: Boolean) {
        val changed = threshold != newThreshold || maxResults != newMaxResults || useGpu != newUseGpu
        threshold = newThreshold
        maxResults = newMaxResults
        useGpu = newUseGpu

        if (changed && !isClosed.get()) {
            setupLiveStreamDetector()
            staticImageDetector?.close()
            staticImageDetector = null
        }
    }

    @Synchronized
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                liveStreamDetector?.close()
                liveStreamDetector = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing liveStreamDetector: ${e.message}", e)
            }
            try {
                staticImageDetector?.close()
                staticImageDetector = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing staticImageDetector: ${e.message}", e)
            }
        }
    }
}
