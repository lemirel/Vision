package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVideo(
    val filePath: String,
    val fileName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val timestampMs: Long
) {
    fun formattedDuration(): String {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }

    fun formattedDate(): String {
        val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    fun formattedSize(): String {
        val mb = fileSizeBytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            val kb = fileSizeBytes.toDouble() / 1024.0
            String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
