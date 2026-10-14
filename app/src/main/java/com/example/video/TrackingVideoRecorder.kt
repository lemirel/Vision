package com.example.video

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.data.model.DetectedObject
import com.example.data.model.FrameTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    }

    private val isRecording = AtomicBoolean(false)
    private var activeRecording: Recording? = null
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    private var currentOutputFile: File? = null
    private var startTimestampMs = 0L
    private val telemetryFrames = mutableListOf<FrameTelemetry>()
    private val telemetryLock = Any()

    fun createVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)
        this.videoCapture = capture
        return capture
    }

    fun isCurrentlyRecording(): Boolean = isRecording.get()

    fun getRecordingDurationMs(): Long {
        if (!isRecording.get() || startTimestampMs == 0L) return 0L
        return SystemClock.uptimeMillis() - startTimestampMs
    }

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

    fun startRecording(
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = videoCapture
        if (capture == null) {
            onError("VideoCapture not initialized")
            return
        }

        if (isRecording.get()) {
            return
        }

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingsDir = File(context.filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val file = File(recordingsDir, "TRACK_$timeStamp.mp4")
            currentOutputFile = file

            synchronized(telemetryLock) {
                telemetryFrames.clear()
            }
            startTimestampMs = SystemClock.uptimeMillis()

            val fileOptions = FileOutputOptions.Builder(file).build()
            val pendingRecording = capture.output.prepareRecording(context, fileOptions)

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pendingRecording.withAudioEnabled()
            }

            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording.set(true)
                        Log.d(TAG, "Hardware video recording started: ${file.absolutePath}")
                        onStarted()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording.set(false)
                        if (event.hasError()) {
                            Log.e(TAG, "Video recording finalize error: ${event.error}")
                        } else {
                            Log.d(TAG, "Video recording finalized successfully: ${file.absolutePath} (${file.length()} bytes)")
                            saveCompanionTelemetry(file)
                            addVideoToMediaStore(file)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            isRecording.set(false)
            onError(e.message ?: "Failed to start recording")
        }
    }

    fun stopRecording(onComplete: (File?) -> Unit) {
        if (!isRecording.get()) {
            onComplete(currentOutputFile)
            return
        }

        isRecording.set(false)
        try {
            activeRecording?.stop()
            activeRecording = null
            val file = currentOutputFile
            onComplete(file)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
            onComplete(currentOutputFile)
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save companion telemetry: ${e.message}")
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
}
