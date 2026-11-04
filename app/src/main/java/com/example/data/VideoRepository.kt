package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.data.model.FrameTelemetry
import com.example.data.model.RecordedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class VideoRepository(private val context: Context) {
    companion object {
        private const val TAG = "VideoRepository"
    }

    private val recordingsDir: File
        get() = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }

    suspend fun getRecordedVideos(): List<RecordedVideo> = withContext(Dispatchers.IO) {
        val dir = recordingsDir
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".mp4") && file.length() > 0 } ?: emptyArray()

        files.mapNotNull { file ->
            try {
                if (file.length() < 1024L) return@mapNotNull null
                var duration = 0L
                val retriever = MediaMetadataRetriever()
                var hasVideoTrack = false
                try {
                    retriever.setDataSource(file.absolutePath)
                    val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    hasVideoTrack = (hasVideo != null && hasVideo != "no")
                    if (!hasVideoTrack) {
                        try { file.delete() } catch (ignored: Throwable) {}
                        return@mapNotNull null
                    }
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = durStr?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    Log.w(TAG, "Error retrieving metadata for ${file.name}: ${e.message}")
                    return@mapNotNull null
                } finally {
                    try { retriever.release() } catch (ignored: Exception) {}
                }

                RecordedVideo(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    durationMs = duration,
                    fileSizeBytes = file.length(),
                    timestampMs = file.lastModified()
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestampMs }
    }

    suspend fun getVideoThumbnail(filePath: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || file.length() < 1024L) return@withContext null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo == null || hasVideo == "no") return@withContext null

            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract thumbnail: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (ignored: Exception) {}
        }
    }

    suspend fun loadVideoTelemetry(filePath: String): List<FrameTelemetry> = withContext(Dispatchers.IO) {
        try {
            val videoFile = File(filePath)
            val metaFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.meta")
            if (!metaFile.exists()) return@withContext emptyList()

            val text = metaFile.readText()
            val array = JSONArray(text)
            val list = mutableListOf<FrameTelemetry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FrameTelemetry(
                        timeMs = obj.optLong("t", 0L),
                        label = obj.optString("l", "Object"),
                        score = obj.optDouble("s", 0.0).toFloat(),
                        left = obj.optDouble("x1", 0.0).toFloat(),
                        top = obj.optDouble("y1", 0.0).toFloat(),
                        right = obj.optDouble("x2", 1.0).toFloat(),
                        bottom = obj.optDouble("y2", 1.0).toFloat(),
                        velocity = obj.optString("v", "Tracked"),
                        trackId = obj.optInt("id", 1)
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading telemetry: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteVideo(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val videoFile = File(filePath)
            val metaFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.meta")
            if (metaFile.exists()) metaFile.delete()
            videoFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete video: ${e.message}")
            false
        }
    }

    suspend fun exportToGallery(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) return@withContext false

            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/VisionTrack")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outStream ->
                    file.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting video to gallery: ${e.message}", e)
            false
        }
    }

    suspend fun deleteAllVideos(): Boolean = withContext(Dispatchers.IO) {
        try {
            recordingsDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }
}
