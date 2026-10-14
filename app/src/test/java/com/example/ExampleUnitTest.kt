package com.example

import android.graphics.RectF
import com.example.data.model.DetectedObject
import com.example.ml.ObjectTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    @Test
    fun testObjectTrackerProminenceSelection() {
        val tracker = ObjectTracker()

        // Create two objects: one small in corner, one large in center
        val smallCornerObj = DetectedObject(
            id = 1,
            label = "Cup",
            score = 0.70f,
            boundingBox = RectF(0.05f, 0.05f, 0.15f, 0.15f) // area = 0.01
        )
        val largeCenterObj = DetectedObject(
            id = 2,
            label = "Person",
            score = 0.92f,
            boundingBox = RectF(0.30f, 0.20f, 0.70f, 0.80f) // area = 0.24, center=(0.5, 0.5)
        )

        val tracked = tracker.processFrame(listOf(smallCornerObj, largeCenterObj), 1000L)
        assertEquals(2, tracked.size)

        val prominent = tracked.firstOrNull { it.isProminent }
        assertNotNull(prominent)
        assertEquals("Person", prominent?.label)
        assertTrue("Prominent object must have isProminent=true", prominent?.isProminent == true)
    }

    @Test
    fun testObjectTrackerManualLock() {
        val tracker = ObjectTracker()

        val cup = DetectedObject(
            id = 1,
            label = "Cup",
            score = 0.60f,
            boundingBox = RectF(0.1f, 0.1f, 0.2f, 0.2f)
        )
        val car = DetectedObject(
            id = 2,
            label = "Car",
            score = 0.95f,
            boundingBox = RectF(0.3f, 0.3f, 0.8f, 0.8f)
        )

        // Initial frame: car should be prominent
        val frame1 = tracker.processFrame(listOf(cup, car), 1000L)
        val cupTrackId = frame1.first { it.label == "Cup" }.id

        // Lock onto Cup
        tracker.lockTarget(cupTrackId)

        // Next frame: cup must remain prominent because it is manually locked
        val frame2 = tracker.processFrame(listOf(cup, car), 1050L)
        val prominent = frame2.firstOrNull { it.isProminent }
        assertEquals(cupTrackId, prominent?.id)
        assertEquals("Cup", prominent?.label)
    }

    @Test
    fun testTrajectoryAccumulation() {
        val tracker = ObjectTracker()

        val pos1 = DetectedObject(
            id = 1,
            label = "Ball",
            score = 0.85f,
            boundingBox = RectF(0.20f, 0.20f, 0.30f, 0.30f)
        )
        val pos2 = DetectedObject(
            id = 1,
            label = "Ball",
            score = 0.85f,
            boundingBox = RectF(0.25f, 0.25f, 0.35f, 0.35f)
        )

        tracker.processFrame(listOf(pos1), 1000L)
        val tracked = tracker.processFrame(listOf(pos2), 1040L)

        val ball = tracked.first { it.label == "Ball" }
        assertTrue("Trajectory should accumulate points", ball.trajectory.size >= 2)
    }

    @Test
    fun testMapToPreviewCoordinates() {
        // Container: 1080x1920, Source: 1080x1920 (1:1 aspect match)
        val rect = com.example.ui.camera.mapToPreviewCoordinates(
            normLeft = 0.1f,
            normTop = 0.2f,
            normRight = 0.5f,
            normBottom = 0.6f,
            containerW = 1080f,
            containerH = 1920f,
            sourceW = 1080,
            sourceH = 1920
        )

        assertEquals(108f, rect.left, 0.5f)
        assertEquals(384f, rect.top, 0.5f)
        assertEquals(540f, rect.right, 0.5f)
        assertEquals(1152f, rect.bottom, 0.5f)
    }

    @Test
    fun testConsistentIdAcrossMultipleFrames() {
        val tracker = ObjectTracker()
        tracker.setMode(com.example.ml.TrackingMode.CONTINUOUS_TRACKING)

        var initialId = -1

        // Simulate 10 video frames with an object moving across the screen
        for (i in 0 until 10) {
            val offsetX = i * 0.02f
            val objectInFrame = DetectedObject(
                id = 999, // detector gave arbitrary raw index
                label = "Laptop",
                score = 0.90f,
                boundingBox = RectF(0.2f + offsetX, 0.3f, 0.4f + offsetX, 0.5f)
            )

            val tracked = tracker.processFrame(listOf(objectInFrame), 1000L + (i * 33L))
            assertEquals(1, tracked.size)
            val trackedObj = tracked.first()

            if (i == 0) {
                initialId = trackedObj.id
                assertTrue("Initial ID should be valid positive int", initialId > 0)
            } else {
                assertEquals(
                    "Track ID must remain strictly consistent across frame $i",
                    initialId,
                    trackedObj.id
                )
                assertEquals(i + 1, trackedObj.ageInFrames)
            }
        }
    }

    @Test
    fun testTemporaryOcclusionPreservesConsistentId() {
        val tracker = ObjectTracker()
        tracker.setMode(com.example.ml.TrackingMode.CONTINUOUS_TRACKING)

        // Frame 1: Object present
        val obj1 = DetectedObject(
            id = 1,
            label = "Cup",
            score = 0.88f,
            boundingBox = RectF(0.4f, 0.4f, 0.5f, 0.5f)
        )
        val f1 = tracker.processFrame(listOf(obj1), 1000L)
        val originalId = f1.first().id

        // Frame 2 & 3: Object occluded (empty detection)
        tracker.processFrame(emptyList(), 1033L)
        tracker.processFrame(emptyList(), 1500L)

        // Frame 4: Object reappears slightly moved
        val objReappear = DetectedObject(
            id = 5,
            label = "Cup",
            score = 0.89f,
            boundingBox = RectF(0.42f, 0.41f, 0.52f, 0.51f)
        )
        val f4 = tracker.processFrame(listOf(objReappear), 1700L)
        assertEquals(1, f4.size)
        assertEquals("Consistent ID must be preserved after temporary occlusion", originalId, f4.first().id)
    }
}
