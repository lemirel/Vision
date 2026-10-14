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
            if (file.exists()) {
                file.delete()
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete video: $filePath", e)
            false
        }
    }
}
