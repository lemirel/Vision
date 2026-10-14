package com.example.ml

import android.graphics.RectF
import com.example.data.model.DetectedObject
import com.example.data.model.TrackPoint
import kotlin.math.atan2
import kotlin.math.hypot

class ObjectTracker {

    private data class TrackedState(
        val id: Int,
        var label: String,
        var score: Float,
        var boundingBox: RectF,
        val trajectory: MutableList<TrackPoint>,
        var velocityPxPerSec: Float = 0f,
        var velocityAngleDeg: Float = 0f,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        val colorArgb: Int
    )

    private val tracks = mutableListOf<TrackedState>()
    private var nextId = 1
    private val lock = Any()

    var lockedTargetId: Int? = null
        private set

    fun lockTarget(id: Int?) {
        synchronized(lock) {
            lockedTargetId = id
        }
    }

    fun processFrame(
        detections: List<DetectedObject>,
        timestampMs: Long,
        canvasWidth: Float = 1080f,
        canvasHeight: Float = 1920f
    ): List<DetectedObject> = synchronized(lock) {
        val now = timestampMs

        // Pair existing tracks with new detections
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()

        // 1. Try to match by IoU & label
        for (dIdx in detections.indices) {
            val det = detections[dIdx]
            var bestTrackIdx = -1
            var bestScore = 0f

            for (tIdx in tracks.indices) {
                if (tIdx in matchedTracks) continue
                val track = tracks[tIdx]

                val iou = computeIoU(track.boundingBox, det.boundingBox)
                val dist = computeNormalizedDistance(track.boundingBox, det.boundingBox)
                val labelMatch = if (track.label.equals(det.label, ignoreCase = true)) 0.2f else 0f
                val matchScore = iou * 0.6f + (1f - dist.coerceIn(0f, 1f)) * 0.3f + labelMatch

                if (matchScore > 0.35f && matchScore > bestScore) {
                    bestScore = matchScore
                    bestTrackIdx = tIdx
                }
            }

            if (bestTrackIdx != -1) {
                matchedTracks.add(bestTrackIdx)
                matchedDetections.add(dIdx)

                val track = tracks[bestTrackIdx]
                // Smooth bounding box (exponential moving average)
                val alpha = 0.65f
                val smoothedBox = RectF(
                    track.boundingBox.left * (1f - alpha) + det.boundingBox.left * alpha,
                    track.boundingBox.top * (1f - alpha) + det.boundingBox.top * alpha,
                    track.boundingBox.right * (1f - alpha) + det.boundingBox.right * alpha,
                    track.boundingBox.bottom * (1f - alpha) + det.boundingBox.bottom * alpha
                )
                track.boundingBox = smoothedBox
                track.label = det.label
                track.score = det.score

                // Update trajectory & velocity
                val curCenterX = (smoothedBox.left + smoothedBox.right) / 2f
                val curCenterY = (smoothedBox.top + smoothedBox.bottom) / 2f

                val dt = ((now - track.lastSeenMs).toFloat() / 1000f).coerceAtLeast(0.01f)
                val lastPoint = track.trajectory.lastOrNull()
                if (lastPoint != null) {
                    val dxPx = (curCenterX - lastPoint.x) * canvasWidth
                    val dyPx = (curCenterY - lastPoint.y) * canvasHeight
                    val distPx = hypot(dxPx, dyPx)
                    val instantVelocity = distPx / dt
                    // Smooth velocity
                    track.velocityPxPerSec = track.velocityPxPerSec * 0.6f + instantVelocity * 0.4f
                    track.velocityAngleDeg = Math.toDegrees(atan2(dyPx.toDouble(), dxPx.toDouble())).toFloat()
                }

                track.trajectory.add(TrackPoint(curCenterX, curCenterY, now))
                if (track.trajectory.size > 24) {
                    track.trajectory.removeAt(0)
                }
                track.lastSeenMs = now
            }
        }

        // 2. Add unmatched detections as new tracks
        for (dIdx in detections.indices) {
            if (dIdx !in matchedDetections) {
                val det = detections[dIdx]
                val centerX = (det.boundingBox.left + det.boundingBox.right) / 2f
                val centerY = (det.boundingBox.top + det.boundingBox.bottom) / 2f

                val newTrack = TrackedState(
                    id = nextId++,
                    label = det.label,
                    score = det.score,
                    boundingBox = RectF(det.boundingBox),
                    trajectory = mutableListOf(TrackPoint(centerX, centerY, now)),
                    velocityPxPerSec = 0f,
                    velocityAngleDeg = 0f,
                    firstSeenMs = now,
                    lastSeenMs = now,
                    colorArgb = det.colorArgb
                )
                tracks.add(newTrack)
            }
        }

        // 3. Remove expired tracks (not seen for > 800ms)
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (now - track.lastSeenMs > 800) {
                if (lockedTargetId == track.id) {
                    lockedTargetId = null // unlock if lost
                }
                iterator.remove()
            }
        }

        // 4. Calculate prominence and identify prominent object
        var maxProminence = -1f
        var candidateProminentId: Int? = null

        val scoredTracks = tracks.map { track ->
            val w = track.boundingBox.width()
            val h = track.boundingBox.height()
            val area = (w * h).coerceIn(0f, 1f)
            val cx = (track.boundingBox.left + track.boundingBox.right) / 2f
            val cy = (track.boundingBox.top + track.boundingBox.bottom) / 2f
            val distFromCenter = hypot(cx - 0.5f, cy - 0.5f).coerceIn(0f, 0.75f)

            // Prominence heuristic: Size (50%) + Confidence (35%) + Center proximity (15%)
            val prominence = (area * 0.50f) + (track.score * 0.35f) + ((0.75f - distFromCenter) / 0.75f * 0.15f)

            if (prominence > maxProminence) {
                maxProminence = prominence
                candidateProminentId = track.id
            }

            Pair(track, prominence)
        }

        val effectiveProminentId = if (lockedTargetId != null && tracks.any { it.id == lockedTargetId }) {
            lockedTargetId
        } else {
            candidateProminentId
        }

        return scoredTracks.map { (track, prominence) ->
            DetectedObject(
                id = track.id,
                label = track.label,
                score = track.score,
                boundingBox = RectF(track.boundingBox),
                prominenceScore = prominence,
                isProminent = (track.id == effectiveProminentId),
                trajectory = track.trajectory.toList(),
                velocityPxPerSec = track.velocityPxPerSec,
                velocityAngleDeg = track.velocityAngleDeg,
                firstSeenTimestampMs = track.firstSeenMs,
                lastSeenTimestampMs = track.lastSeenMs,
                colorArgb = if (track.id == effectiveProminentId) 0xFFFFB703.toInt() else track.colorArgb
            )
        }
    }

    fun findObjectAtNormalizedCoord(x: Float, y: Float): DetectedObject? = synchronized(lock) {
        val currentTracks = tracks.map { track ->
            DetectedObject(
                id = track.id,
                label = track.label,
                score = track.score,
                boundingBox = RectF(track.boundingBox)
            )
        }
        return currentTracks.firstOrNull { it.boundingBox.contains(x, y) }
    }

    fun reset() = synchronized(lock) {
        tracks.clear()
        lockedTargetId = null
    }

    private fun computeIoU(b1: RectF, b2: RectF): Float {
        val left = maxOf(b1.left, b2.left)
        val top = maxOf(b1.top, b2.top)
        val right = minOf(b1.right, b2.right)
        val bottom = minOf(b1.bottom, b2.bottom)

        val intersectionW = (right - left).coerceAtLeast(0f)
        val intersectionH = (bottom - top).coerceAtLeast(0f)
        val intersectionArea = intersectionW * intersectionH

        val area1 = b1.width() * b1.height()
        val area2 = b2.width() * b2.height()
        val unionArea = area1 + area2 - intersectionArea

        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    private fun computeNormalizedDistance(b1: RectF, b2: RectF): Float {
        val c1x = (b1.left + b1.right) / 2f
        val c1y = (b1.top + b1.bottom) / 2f
        val c2x = (b2.left + b2.right) / 2f
        val c2y = (b2.top + b2.bottom) / 2f
        return hypot(c1x - c2x, c1y - c2y)
    }
}
