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

/**
 * Helper class to initialize and manage MediaPipe Object Detector task.
 * Supports both real-time LIVE_STREAM and single IMAGE detection modes.
 */
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

    /**
     * Initializes the Object Detector in LIVE_STREAM mode.
     * Results are returned asynchronously via onLiveDetectionResult callback.
     */
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

                // Use setModelAssetPath to load from assets directly
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
                        
                        // Parse detection results and map to our domain model
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
                Log.d(TAG, "MediaPipe Live Detector initialized successfully (GPU=$useGpu)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize live detector: ${e.message}", e)
                isInitialized = false
                onStatusChanged?.invoke(DetectorStatus.Error(e.message ?: "Failed to initialize detector"))
                onError?.invoke(e.message ?: "Failed to initialize detector")
            }
        }
    }

    /**
     * Processes a single camera frame for object detection.
     * @param bitmap The camera frame as a Bitmap.
     * @param timestampMs The presentation timestamp of the frame.
     */
    fun detectLiveStream(bitmap: Bitmap, timestampMs: Long) {
        synchronized(detectorLock) {
            val detector = liveDetector ?: return
            if (!isInitialized) return
            
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                detector.detectAsync(mpImage, timestampMs)
            } catch (e: Exception) {
                Log.w(TAG, "detectLiveStream error: ${e.message}")
            }
        }
    }

    /**
     * Detects objects in a static image (Blocking call).
     */
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

    /**
     * Updates detector settings dynamically.
     */
    fun updateSettings(threshold: Float, maxResults: Int, useGpu: Boolean) {
        if (this.threshold == threshold && this.maxResults == maxResults && this.useGpu == useGpu) return
        
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
            
            // MediaPipe returns bounding box in pixels. 
            // Normalize coordinates [0f, 1f] for UI rendering.
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

    fun close() {
        synchronized(detectorLock) {
            try {
                liveDetector?.close()
                liveDetector = null
                staticDetector?.close()
                staticDetector = null
                isInitialized = false
            } catch (e: Exception) {
                Log.w(TAG, "Error closing detector: ${e.message}")
            }
        }
    }
}
