package com.example.ml

import android.graphics.RectF
import com.example.data.model.DetectedObject
import kotlin.math.abs
import kotlin.math.hypot

class ObjectTracker(
    private val maxHistory: Int = 10,
    private val iouThreshold: Float = 0.25f
) {
    private var nextTrackId = 1
    private val trackedObjects = mutableListOf<TrackedState>()
    var lockedTargetId: Int? = null

    private data class TrackedState(
        val id: Int,
        var label: String,
        var score: Float,
        var box: RectF,
        var lastTimestampMs: Long,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        val history: MutableList<RectF> = mutableListOf(),
        var missedFrames: Int = 0
    )

    fun processFrame(
        detections: List<DetectedObject>,
        timestampMs: Long
    ): List<DetectedObject> {
        // Match detections with existing tracks
        val unmatchedTracks = trackedObjects.toMutableList()
        val matchedTracks = mutableListOf<Pair<TrackedState, DetectedObject>>()

        for (det in detections) {
            var bestTrack: TrackedState? = null
            var bestIou = iouThreshold

            for (track in unmatchedTracks) {
                val iou = calculateIoU(track.box, det.boundingBox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestTrack = track
                }
            }

            if (bestTrack != null) {
                matchedTracks.add(Pair(bestTrack, det))
                unmatchedTracks.remove(bestTrack)
            } else {
                // New track
                val newTrack = TrackedState(
                    id = nextTrackId++,
                    label = det.label,
                    score = det.score,
                    box = det.boundingBox,
                    lastTimestampMs = timestampMs
                ).apply {
                    history.add(det.boundingBox)
                }
                trackedObjects.add(newTrack)
            }
        }

        // Update matched tracks
        for ((track, det) in matchedTracks) {
            val dt = (timestampMs - track.lastTimestampMs).coerceAtLeast(1L).toFloat() / 1000f
            val oldCx = (track.box.left + track.box.right) / 2f
            val oldCy = (track.box.top + track.box.bottom) / 2f
            val newCx = (det.boundingBox.left + det.boundingBox.right) / 2f
            val newCy = (det.boundingBox.top + det.boundingBox.bottom) / 2f

            track.velocityX = (newCx - oldCx) / dt
            track.velocityY = (newCy - oldCy) / dt
            track.label = det.label
            track.score = det.score
            track.box = det.boundingBox
            track.lastTimestampMs = timestampMs
            track.missedFrames = 0

            track.history.add(det.boundingBox)
            if (track.history.size > maxHistory) {
                track.history.removeAt(0)
            }
        }

        // Increment missed frames for unmatched tracks and prune dead tracks
        for (track in unmatchedTracks) {
            track.missedFrames++
        }
        trackedObjects.removeAll { it.missedFrames > 5 }

        // Find prominent object: center proximity + area + score
        var highestRank = -1f
        var prominentId: Int? = null

        for (track in trackedObjects) {
            val cx = (track.box.left + track.box.right) / 2f
            val cy = (track.box.top + track.box.bottom) / 2f
            val distFromCenter = hypot(cx - 0.5f, cy - 0.5f)
            val centerScore = (1f - distFromCenter * 1.5f).coerceIn(0f, 1f)
            val area = (track.box.right - track.box.left) * (track.box.bottom - track.box.top)

            val rank = (area * 0.45f) + (centerScore * 0.35f) + (track.score * 0.20f)
            if (rank > highestRank) {
                highestRank = rank
                prominentId = track.id
            }
        }

        // If locked target is set and alive, override prominent
        if (lockedTargetId != null && trackedObjects.any { it.id == lockedTargetId }) {
            prominentId = lockedTargetId
        }

        val colors = intArrayOf(
            0xFF00E5FF.toInt(), // Cyan
            0xFF39FF14.toInt(), // Neon Green
            0xFFFF007F.toInt(), // Magenta
            0xFF00F0FF.toInt(), // Sky
            0xFFBD00FF.toInt(), // Purple
            0xFFFFD700.toInt()  // Gold
        )

        return trackedObjects.map { track ->
            val isProm = (track.id == prominentId)
            val color = colors[abs(track.id) % colors.size]

            DetectedObject(
                id = track.id,
                label = track.label,
                score = track.score,
                boundingBox = track.box,
                isProminent = isProm,
                colorArgb = if (isProm) 0xFFFFB703.toInt() else color,
                velocityX = track.velocityX,
                velocityY = track.velocityY,
                trajectory = track.history.toList()
            )
        }
    }

    fun toggleLockTarget(targetId: Int) {
        lockedTargetId = if (lockedTargetId == targetId) null else targetId
    }

    fun clear() {
        trackedObjects.clear()
        lockedTargetId = null
        nextTrackId = 1
    }

    private fun calculateIoU(boxA: RectF, boxB: RectF): Float {
        val xA = maxOf(boxA.left, boxB.left)
        val yA = maxOf(boxA.top, boxB.top)
        val xB = minOf(boxA.right, boxB.right)
        val yB = minOf(boxA.bottom, boxB.bottom)

        val interArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val boxAArea = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val boxBArea = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)
        val unionArea = boxAArea + boxBArea - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
