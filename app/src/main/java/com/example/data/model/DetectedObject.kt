package com.example.data.model

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class DetectedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val boundingBox: RectF, // Normalized coordinates [0.0, 1.0]
    val isProminent: Boolean = false,
    val colorArgb: Int = 0xFF00E5FF.toInt(),
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val trajectory: List<RectF> = emptyList(),
    val trackingDurationMs: Long = 0L
) {
    val area: Float
        get() = (boundingBox.right - boundingBox.left) * (boundingBox.bottom - boundingBox.top)

    val isTrackedAcrossFrames: Boolean
        get() = trajectory.size > 1

    fun formattedScore(): String = "${(score * 100).toInt()}%"

    fun formattedTrackingDuration(): String = "${(trackingDurationMs / 1000).coerceAtLeast(0)}s"

    fun formattedVelocity(): String {
        val speed = kotlin.math.sqrt(velocityX * velocityX + velocityY * velocityY)
        return when {
            speed < 0.01f -> "Static"
            speed < 0.05f -> "Slow"
            speed < 0.15f -> "Moving"
            else -> "Fast (${String.format(java.util.Locale.US, "%.1f", speed * 100)} px/s)"
        }
    }
}
