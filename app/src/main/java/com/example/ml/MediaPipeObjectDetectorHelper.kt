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

class MediaPipeObjectDetectorHelper(
    private val context: Context,
    var threshold: Float = 0.45f,
    var maxResults: Int = 6,
    var useGpu: Boolean = false,
    val onLiveDetectionResult: ((List<DetectedObject>, Long, Long) -> Unit)? = null,
    val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MediaPipeDetector"
        const val MODEL_PATH = "efficientdet_lite0.tflite"
    }

    private var liveStreamDetector: ObjectDetector? = null
    private var staticImageDetector: ObjectDetector? = null

    init {
        setupLiveStreamDetector()
    }

    @Synchronized
    fun setupLiveStreamDetector() {
        try {
            liveStreamDetector?.close()
            liveStreamDetector = null

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
                    val inferenceTime = SystemClock.uptimeMillis() - (result.timestampMs())
                    val detectedObjects = convertResult(result, inputImage.width, inputImage.height)
                    onLiveDetectionResult?.invoke(detectedObjects, inferenceTime, result.timestampMs())
                }
                .setErrorListener { error ->
                    Log.e(TAG, "LiveStream error: ${error.message}", error)
                    onError?.invoke(error.message ?: "Unknown MediaPipe error")
                }
                .build()

            liveStreamDetector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "LiveStream ObjectDetector initialized successfully (GPU=$useGpu)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LiveStream ObjectDetector, retrying with CPU: ${e.message}", e)
            if (useGpu) {
                // Auto-fallback to CPU
                useGpu = false
                setupLiveStreamDetector()
            } else {
                onError?.invoke("Could not load MediaPipe model: ${e.localizedMessage}")
            }
        }
    }

    @Synchronized
    private fun getOrCreateStaticDetector(): ObjectDetector? {
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

    private var lastLiveTimestampMs = 0L

    fun detectLiveStream(bitmap: Bitmap, timestampMs: Long) {
        val detector = liveStreamDetector ?: return
        try {
            val validTimestamp = if (timestampMs <= lastLiveTimestampMs) {
                lastLiveTimestampMs + 1
            } else {
                timestampMs
            }
            lastLiveTimestampMs = validTimestamp
            val mpImage = BitmapImageBuilder(bitmap).build()
            detector.detectAsync(mpImage, validTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "detectLiveStream failed: ${e.message}", e)
        }
    }

    fun detectImage(bitmap: Bitmap): List<DetectedObject> {
        val detector = getOrCreateStaticDetector() ?: return emptyList()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = detector.detect(mpImage)
            convertResult(result, bitmap.width, bitmap.height)
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

        if (changed) {
            setupLiveStreamDetector()
            staticImageDetector?.close()
            staticImageDetector = null
        }
    }

    fun close() {
        try {
            liveStreamDetector?.close()
            staticImageDetector?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detectors: ${e.message}", e)
        }
    }
}
