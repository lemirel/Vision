package com.example.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.example.data.model.DetectedObject
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.File
import java.io.FileOutputStream

class MediaPipeObjectDetectorHelper(
    private val context: Context,
    var threshold: Float = 0.40f,
    var maxResults: Int = 6,
    var useGpu: Boolean = false,
    val onLiveDetectionResult: ((List<DetectedObject>, latencyMs: Long, timestampMs: Long) -> Unit)? = null,
    val onError: ((String) -> Unit)? = null,
    val onStatusChanged: ((DetectorStatus) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MediaPipeDetector"
        private const val MODEL_FILE_NAME = "efficientdet_lite0.tflite"
    }

    private var liveDetector: ObjectDetector? = null
    private var staticDetector: ObjectDetector? = null
    private val detectorLock = Any()
    private var isInitialized = false

    init {
        setupLiveStreamDetector()
    }

    fun setupLiveStreamDetector() {
        synchronized(detectorLock) {
            onStatusChanged?.invoke(DetectorStatus.Initializing)
            try {
                liveDetector?.close()
                liveDetector = null

                val baseOptionsBuilder = BaseOptions.builder()
                if (useGpu) {
                    baseOptionsBuilder.setDelegate(Delegate.GPU)
                } else {
                    baseOptionsBuilder.setDelegate(Delegate.CPU)
                }

                ensureModelFileExists()
                baseOptionsBuilder.setModelAssetPath(MODEL_FILE_NAME)

                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setScoreThreshold(threshold)
                    .setMaxResults(maxResults)
                    .setResultListener { result: ObjectDetectorResult, inputImage ->
                        val now = SystemClock.uptimeMillis()
                        val timestampMs = result.timestampMs()
                        val latency = (now - timestampMs).coerceAtLeast(0L)
                        val detections = parseDetections(result, inputImage.width, inputImage.height)
                        onLiveDetectionResult?.invoke(detections, latency, timestampMs)
                    }
                    .setErrorListener { error ->
                        Log.e(TAG, "MediaPipe Live Stream Error: ${error.message}")
                        onError?.invoke(error.message ?: "Detector runtime error")
                    }
                    .build()

                liveDetector = ObjectDetector.createFromOptions(context, options)
                isInitialized = true
                onStatusChanged?.invoke(DetectorStatus.Ready)
                Log.d(TAG, "MediaPipe Live Detector initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize live detector: ${e.message}", e)
                isInitialized = false
                onStatusChanged?.invoke(DetectorStatus.Error(e.message ?: "Failed to initialize detector"))
                onError?.invoke(e.message ?: "Failed to initialize detector")
            }
        }
    }

    fun detectLiveStream(bitmap: Bitmap, timestampMs: Long) {
        synchronized(detectorLock) {
            val detector = liveDetector ?: return
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                detector.detectAsync(mpImage, timestampMs)
            } catch (e: Exception) {
                Log.w(TAG, "detectLiveStream error: ${e.message}")
            }
        }
    }

    fun detectImage(bitmap: Bitmap): List<DetectedObject> {
        synchronized(detectorLock) {
            try {
                if (staticDetector == null) {
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetPath(MODEL_FILE_NAME)
                        .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
                        .build()

                    val options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.IMAGE)
                        .setScoreThreshold(threshold)
                        .setMaxResults(maxResults)
                        .build()

                    staticDetector = ObjectDetector.createFromOptions(context, options)
                }

                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = staticDetector!!.detect(mpImage)
                return parseDetections(result, bitmap.width, bitmap.height)
            } catch (e: Exception) {
                Log.e(TAG, "Static image detection error: ${e.message}", e)
                return emptyList()
            }
        }
    }

    fun updateSettings(threshold: Float, maxResults: Int, useGpu: Boolean) {
        this.threshold = threshold
        this.maxResults = maxResults
        this.useGpu = useGpu
        setupLiveStreamDetector()
        synchronized(detectorLock) {
            staticDetector?.close()
            staticDetector = null
        }
    }

    private fun parseDetections(
        result: ObjectDetectorResult,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedObject> {
        val detected = mutableListOf<DetectedObject>()
        val widthF = imageWidth.toFloat()
        val heightF = imageHeight.toFloat()

        result.detections().forEachIndexed { index, detection ->
            val category = detection.categories().firstOrNull()
            val label = category?.categoryName() ?: "Object"
            val score = category?.score() ?: 0.5f

            val box = detection.boundingBox()
            // Normalize coordinates [0f, 1f]
            val left = (box.left / widthF).coerceIn(0f, 1f)
            val top = (box.top / heightF).coerceIn(0f, 1f)
            val right = (box.right / widthF).coerceIn(left, 1f)
            val bottom = (box.bottom / heightF).coerceIn(top, 1f)

            detected.add(
                DetectedObject(
                    id = index + 1,
                    label = label,
                    score = score,
                    boundingBox = RectF(left, top, right, bottom)
                )
            )
        }
        return detected
    }

    private fun ensureModelFileExists() {
        val assetDir = File(context.filesDir, "models")
        if (!assetDir.exists()) assetDir.mkdirs()
        val localModel = File(assetDir, MODEL_FILE_NAME)
        if (!localModel.exists() || localModel.length() == 0L) {
            try {
                context.assets.open(MODEL_FILE_NAME).use { input ->
                    FileOutputStream(localModel).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Model copy from assets note: ${e.message}")
            }
        }
    }

    fun close() {
        synchronized(detectorLock) {
            try {
                liveDetector?.close()
                liveDetector = null
                staticDetector?.close()
                staticDetector = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing detector: ${e.message}")
            }
        }
    }
}
