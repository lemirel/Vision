package com.example.data.model

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.hypot

data class TrackPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long
)

data class DetectedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val boundingBox: RectF, // Normalized coordinates [0..1]
    val prominenceScore: Float = 0f,
    val isProminent: Boolean = false,
    val trajectory: List<TrackPoint> = emptyList(),
    val velocityPxPerSec: Float = 0f,
    val velocityAngleDeg: Float = 0f,
    val firstSeenTimestampMs: Long = System.currentTimeMillis(),
    val lastSeenTimestampMs: Long = System.currentTimeMillis(),
    val ageInFrames: Int = 1,
    val isTrackedAcrossFrames: Boolean = true,
    val colorArgb: Int = 0xFF00F5D4.toInt()
) {
    val centerX: Float
        get() = (boundingBox.left + boundingBox.right) / 2f

    val centerY: Float
        get() = (boundingBox.top + boundingBox.bottom) / 2f

    val width: Float
        get() = (boundingBox.right - boundingBox.left).coerceAtLeast(0.001f)

    val height: Float
        get() = (boundingBox.bottom - boundingBox.top).coerceAtLeast(0.001f)

    val area: Float
        get() = width * height

    val centerPoint: PointF
        get() = PointF(centerX, centerY)

    val trackingDurationMs: Long
        get() = (lastSeenTimestampMs - firstSeenTimestampMs).coerceAtLeast(0L)

    fun formattedScore(): String = "${(score * 100).toInt()}%"

    fun formattedVelocity(): String {
        return if (velocityPxPerSec < 5f) {
            "Stationary"
        } else {
            "${velocityPxPerSec.toInt()} px/s"
        }
    }

    fun formattedTrackingDuration(): String {
        val sec = trackingDurationMs / 1000f
        return if (sec >= 1f) String.format(java.util.Locale.US, "%.1fs", sec) else "${trackingDurationMs}ms"
    }
}
