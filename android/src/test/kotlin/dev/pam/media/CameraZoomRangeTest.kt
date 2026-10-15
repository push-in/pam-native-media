package dev.pam.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraZoomRangeTest {
    @Test
    fun maxZoomPropertyCapsTheHardwareRange() {
        assertEquals(CameraZoomRange(1f, 8f), CameraZoomRange.create(1f, 8f, 16f))
        assertEquals(CameraZoomRange(0.5f, 4f), CameraZoomRange.create(0.5f, 10f, 4f))
        // A cap below the lens minimum collapses to the minimum instead of inverting the range.
        assertEquals(CameraZoomRange(2f, 2f), CameraZoomRange.create(2f, 8f, 1f))
        assertEquals(CameraZoomRange(1f, 8f), CameraZoomRange.create(1f, 8f, Float.NaN))
        assertEquals(CameraZoomRange(1f, 1f), CameraZoomRange.create(Float.NaN, 0f, 16f))
    }

    @Test
    fun resetTargetsOneTimesInsideTheRange() {
        assertEquals(1f, CameraZoomRange(0.5f, 8f).resetValue(), 0f)
        assertEquals(2f, CameraZoomRange(2f, 8f).resetValue(), 0f)
        assertEquals(0.8f, CameraZoomRange(0.5f, 0.8f).resetValue(), 0f)
    }

    @Test
    fun pinchMultipliesTheStartRatioAndClamps() {
        val range = CameraZoomRange(1f, 4f)
        assertEquals(3f, range.pinch(1.5f, 2f), 0.0001f)
        assertEquals(4f, range.pinch(2f, 10f), 0f)
        assertEquals(1f, range.pinch(2f, 0.1f), 0f)
        assertEquals(2f, range.pinch(2f, Float.NaN), 0f)
        assertEquals(1f, range.clamp(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun trackerAccumulatesIncrementalScaleFactorsSinceBegin() {
        val range = CameraZoomRange(1f, 8f)
        val tracker = PinchZoomTracker()
        assertNull(tracker.scale(2f, range))
        tracker.begin(2f)
        assertTrue(tracker.active)
        assertEquals(2.4f, tracker.scale(1.2f, range)!!, 0.0001f)
        assertEquals(3.6f, tracker.scale(1.5f, range)!!, 0.0001f)
        assertEquals(1.8f, tracker.scale(0.5f, range)!!, 0.0001f)
        assertEquals(1.8f, tracker.scale(Float.NaN, range)!!, 0.0001f)
        tracker.end()
        assertFalse(tracker.active)
        assertNull(tracker.scale(2f, range))
    }

    @Test
    fun overshootDoesNotDelayReversingThePinch() {
        val range = CameraZoomRange(1f, 4f)
        val tracker = PinchZoomTracker()
        tracker.begin(2f)
        assertEquals(4f, tracker.scale(10f, range)!!, 0f)
        // Pinching back in from the clamp reacts at once instead of unwinding the 10x overshoot.
        assertEquals(2f, tracker.scale(0.5f, range)!!, 0.0001f)
    }
}
