package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.data.model.RecordedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(private val context: Context) {
    companion object {
        private const val TAG = "VideoRepository"
    }

    private val recordingsDir: File
        get() = File(context.filesDir, "recordings").apply {
            if (!exists()) mkdirs()
        }

    suspend fun getRecordedVideos(): List<RecordedVideo> = withContext(Dispatchers.IO) {
        val files = recordingsDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        files.mapNotNull { file ->
            try {
                var durationMs = 0L
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = durStr?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract metadata for ${file.name}: ${e.message}")
                } finally {
                    retriever.release()
                }

                RecordedVideo(
                    id = file.name,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    durationMs = durationMs,
                    timestamp = file.lastModified()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing video file: ${file.name}", e)
                null
            }
        }
    }

    suspend fun getVideoThumbnail(filePath: String): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            retriever.frameAtTime
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get thumbnail for $filePath: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    suspend fun deleteVideo(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.meta")
            if (metaFile.exists()) metaFile.delete()

            if (file.exists()) {
                file.delete()
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete video: $filePath", e)
            false
        }
    }

    suspend fun loadVideoTelemetry(filePath: String): List<com.example.data.model.FrameTelemetry> = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.meta")
        if (!metaFile.exists()) return@withContext emptyList()

        try {
            val content = metaFile.readText()
            val jsonArray = org.json.JSONArray(content)
            val result = mutableListOf<com.example.data.model.FrameTelemetry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(
                    com.example.data.model.FrameTelemetry(
                        timeMs = obj.getLong("t"),
                        label = obj.getString("l"),
                        score = obj.getDouble("s").toFloat(),
                        left = obj.getDouble("x1").toFloat(),
                        top = obj.getDouble("y1").toFloat(),
                        right = obj.getDouble("x2").toFloat(),
                        bottom = obj.getDouble("y2").toFloat(),
                        velocity = obj.optString("v", "Stationary"),
                        trackId = obj.optInt("id", 1)
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing telemetry file: ${metaFile.name}", e)
            emptyList()
        }
    }
}
