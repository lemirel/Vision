package com.example.ml

import android.graphics.RectF
import com.example.data.model.DetectedObject
import com.example.data.model.TrackPoint
import kotlin.math.atan2
import kotlin.math.hypot

enum class TrackingMode {
    CONTINUOUS_TRACKING, // MediaPipe Box Tracking: maintains consistent IDs across multiple video frames
    DETECTION_ONLY       // Raw per-frame independent detections
}

/**
 * High-performance Object Tracker implementing the MediaPipe Detection + Box Tracking
 * architecture for continuous, jitter-free object tracking with consistent IDs across
 * video frames.
 */
class ObjectTracker {

    private data class TrackedState(
        val id: Int,
        var label: String,
        var score: Float,
        var boundingBox: RectF,
        val trajectory: MutableList<TrackPoint>,
        var velocityPxPerSec: Float = 0f,
        var velocityAngleDeg: Float = 0f,
        var vxNormalized: Float = 0f, // Normalized velocity dx/sec
        var vyNormalized: Float = 0f, // Normalized velocity dy/sec
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var missedFramesCount: Int = 0,
        var totalFramesTracked: Int = 1,
        val colorArgb: Int
    )

    private val activeTracks = mutableListOf<TrackedState>()
    private val lostTracksHistory = mutableListOf<TrackedState>() // Re-identification pool for occluded objects
    private var nextId = 1
    private val lock = Any()

    var trackingMode: TrackingMode = TrackingMode.CONTINUOUS_TRACKING
        private set

    var lockedTargetId: Int? = null
        private set

    fun setMode(mode: TrackingMode) {
        synchronized(lock) {
            trackingMode = mode
            if (mode == TrackingMode.DETECTION_ONLY) {
                reset()
            }
        }
    }

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
        if (trackingMode == TrackingMode.DETECTION_ONLY) {
            return processDetectionOnly(detections)
        }

        val now = timestampMs

        // 1. Prediction step: Predict position for each active track based on estimated velocity
        for (track in activeTracks) {
            val dt = ((now - track.lastSeenMs).toFloat() / 1000f).coerceIn(0f, 0.5f)
            if (dt > 0f && (track.vxNormalized != 0f || track.vyNormalized != 0f)) {
                val dx = track.vxNormalized * dt
                val dy = track.vyNormalized * dt
                track.boundingBox = RectF(
                    (track.boundingBox.left + dx).coerceIn(0f, 1f),
                    (track.boundingBox.top + dy).coerceIn(0f, 1f),
                    (track.boundingBox.right + dx).coerceIn(0f, 1f),
                    (track.boundingBox.bottom + dy).coerceIn(0f, 1f)
                )
            }
        }

        // 2. Association step: Match existing active tracks with new incoming detections
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()

        // Pass A: Match active tracks by IoU, spatial proximity, and class label
        for (dIdx in detections.indices) {
            val det = detections[dIdx]
            var bestTrackIdx = -1
            var bestScore = 0f

            for (tIdx in activeTracks.indices) {
                if (tIdx in matchedTracks) continue
                val track = activeTracks[tIdx]

                val iou = computeIoU(track.boundingBox, det.boundingBox)
                val dist = computeNormalizedDistance(track.boundingBox, det.boundingBox)
                val isSameClass = track.label.equals(det.label, ignoreCase = true)
                val labelBonus = if (isSameClass) 0.25f else 0f

                // Affinity score: combination of IoU, center distance, and class label
                val matchScore = (iou * 0.50f) + ((1f - dist.coerceIn(0f, 1f)) * 0.35f) + labelBonus

                if (matchScore > 0.32f && matchScore > bestScore) {
                    bestScore = matchScore
                    bestTrackIdx = tIdx
                }
            }

            if (bestTrackIdx != -1) {
                matchedTracks.add(bestTrackIdx)
                matchedDetections.add(dIdx)

                val track = activeTracks[bestTrackIdx]
                track.missedFramesCount = 0
                track.totalFramesTracked++

                // Exponential Moving Average (EMA) smoothing for temporal consistency and anti-jitter
                val alpha = 0.70f
                track.boundingBox = RectF(
                    track.boundingBox.left * (1f - alpha) + det.boundingBox.left * alpha,
                    track.boundingBox.top * (1f - alpha) + det.boundingBox.top * alpha,
                    track.boundingBox.right * (1f - alpha) + det.boundingBox.right * alpha,
                    track.boundingBox.bottom * (1f - alpha) + det.boundingBox.bottom * alpha
                )
                track.label = det.label
                track.score = det.score

                // Update trajectory & velocity
                val curCenterX = (track.boundingBox.left + track.boundingBox.right) / 2f
                val curCenterY = (track.boundingBox.top + track.boundingBox.bottom) / 2f

                val dt = ((now - track.lastSeenMs).toFloat() / 1000f).coerceAtLeast(0.016f)
                val lastPoint = track.trajectory.lastOrNull()
                if (lastPoint != null) {
                    val dxNorm = curCenterX - lastPoint.x
                    val dyNorm = curCenterY - lastPoint.y

                    track.vxNormalized = track.vxNormalized * 0.5f + (dxNorm / dt) * 0.5f
                    track.vyNormalized = track.vyNormalized * 0.5f + (dyNorm / dt) * 0.5f

                    val dxPx = dxNorm * canvasWidth
                    val dyPx = dyNorm * canvasHeight
                    val distPx = hypot(dxPx, dyPx)
                    val instantVelocity = distPx / dt

                    track.velocityPxPerSec = track.velocityPxPerSec * 0.6f + instantVelocity * 0.4f
                    track.velocityAngleDeg = Math.toDegrees(atan2(dyPx.toDouble(), dxPx.toDouble())).toFloat()
                }

                track.trajectory.add(TrackPoint(curCenterX, curCenterY, now))
                if (track.trajectory.size > 28) {
                    track.trajectory.removeAt(0)
                }
                track.lastSeenMs = now
            }
        }

        // Pass B: Re-identification of lost/occluded tracks
        for (dIdx in detections.indices) {
            if (dIdx in matchedDetections) continue
            val det = detections[dIdx]

            var bestLostIdx = -1
            var bestScore = 0f

            for (lIdx in lostTracksHistory.indices) {
                val lost = lostTracksHistory[lIdx]
                if (now - lost.lastSeenMs > 1500) continue // Expired re-ID window

                val iou = computeIoU(lost.boundingBox, det.boundingBox)
                val dist = computeNormalizedDistance(lost.boundingBox, det.boundingBox)
                val isSameClass = lost.label.equals(det.label, ignoreCase = true)
                if (!isSameClass) continue

                val reidScore = (iou * 0.4f) + ((1f - dist.coerceIn(0f, 1f)) * 0.6f)
                if (reidScore > 0.40f && reidScore > bestScore) {
                    bestScore = reidScore
                    bestLostIdx = lIdx
                }
            }

            if (bestLostIdx != -1) {
                // Revive track with the SAME consistent ID!
                val restoredTrack = lostTracksHistory.removeAt(bestLostIdx)
                matchedDetections.add(dIdx)

                restoredTrack.boundingBox = RectF(det.boundingBox)
                restoredTrack.label = det.label
                restoredTrack.score = det.score
                restoredTrack.lastSeenMs = now
                restoredTrack.missedFramesCount = 0
                restoredTrack.totalFramesTracked++

                val cx = (det.boundingBox.left + det.boundingBox.right) / 2f
                val cy = (det.boundingBox.top + det.boundingBox.bottom) / 2f
                restoredTrack.trajectory.add(TrackPoint(cx, cy, now))

                activeTracks.add(restoredTrack)
            }
        }

        // Pass C: Register genuinely new objects with persistent monotonic IDs
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
                    missedFramesCount = 0,
                    totalFramesTracked = 1,
                    colorArgb = det.colorArgb
                )
                activeTracks.add(newTrack)
            }
        }

        // 3. Mark missed active tracks and transition to lost pool if missing
        val activeIterator = activeTracks.iterator()
        while (activeIterator.hasNext()) {
            val track = activeIterator.next()
            if (now - track.lastSeenMs > 80) { // Missed in this frame
                track.missedFramesCount++
            }

            if (now - track.lastSeenMs > 450) { // Lost from active view
                if (lockedTargetId == track.id && now - track.lastSeenMs > 1200) {
                    lockedTargetId = null
                }
                lostTracksHistory.add(track)
                activeIterator.remove()
            }
        }

        // Clean expired tracks from re-ID pool
        lostTracksHistory.removeAll { now - it.lastSeenMs > 2000 }

        // 4. Prominence determination for the active scene
        var maxProminence = -1f
        var candidateProminentId: Int? = null

        val scoredTracks = activeTracks.map { track ->
            val w = track.boundingBox.width()
            val h = track.boundingBox.height()
            val area = (w * h).coerceIn(0f, 1f)
            val cx = (track.boundingBox.left + track.boundingBox.right) / 2f
            val cy = (track.boundingBox.top + track.boundingBox.bottom) / 2f
            val distFromCenter = hypot(cx - 0.5f, cy - 0.5f).coerceIn(0f, 0.75f)

            // Bonus for sustained tracking longevity
            val trackLongevityBonus = (track.totalFramesTracked / 50f).coerceIn(0f, 0.10f)
            val prominence = (area * 0.45f) + (track.score * 0.30f) + ((0.75f - distFromCenter) / 0.75f * 0.15f) + trackLongevityBonus

            if (prominence > maxProminence) {
                maxProminence = prominence
                candidateProminentId = track.id
            }

            Pair(track, prominence)
        }

        val effectiveProminentId = if (lockedTargetId != null && activeTracks.any { it.id == lockedTargetId }) {
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
                ageInFrames = track.totalFramesTracked,
                isTrackedAcrossFrames = true,
                colorArgb = if (track.id == effectiveProminentId) 0xFFFFB703.toInt() else track.colorArgb
            )
        }
    }

    private fun processDetectionOnly(detections: List<DetectedObject>): List<DetectedObject> {
        var maxProminence = -1f
        var prominentIdx = -1

        val scored = detections.mapIndexed { index, det ->
            val w = det.boundingBox.width()
            val h = det.boundingBox.height()
            val area = (w * h).coerceIn(0f, 1f)
            val cx = (det.boundingBox.left + det.boundingBox.right) / 2f
            val cy = (det.boundingBox.top + det.boundingBox.bottom) / 2f
            val distFromCenter = hypot(cx - 0.5f, cy - 0.5f).coerceIn(0f, 0.75f)
            val prominence = (area * 0.55f) + (det.score * 0.35f) + ((0.75f - distFromCenter) / 0.75f * 0.10f)

            if (prominence > maxProminence) {
                maxProminence = prominence
                prominentIdx = index
            }
            Pair(det, prominence)
        }

        return scored.mapIndexed { index, (det, prominence) ->
            det.copy(
                id = index + 1,
                prominenceScore = prominence,
                isProminent = (index == prominentIdx),
                isTrackedAcrossFrames = false
            )
        }
    }

    fun findObjectAtNormalizedCoord(x: Float, y: Float): DetectedObject? = synchronized(lock) {
        val currentTracks = activeTracks.map { track ->
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
        activeTracks.clear()
        lostTracksHistory.clear()
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
