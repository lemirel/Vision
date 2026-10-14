package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedVideo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val timestamp: Long,
    val targetLabel: String = "Prominent Object",
    val confidence: Float = 0f
) {
    fun formattedDuration(): String {
        val totalSec = (durationMs / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }

    fun formattedDate(): String {
        return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun formattedSize(): String {
        val kb = fileSizeBytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
