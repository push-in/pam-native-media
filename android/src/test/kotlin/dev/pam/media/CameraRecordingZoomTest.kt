package dev.pam.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRecordingZoomTest {
    @Test
    fun defaultAndInvalidConfigurationsDoNotAnimate() {
        assertNull(RecordingZoomPlan.create(1f, 0, 1f, 8f))
        assertNull(RecordingZoomPlan.create(2.8f, 0, 1f, 8f))
        assertNull(RecordingZoomPlan.create(Float.NaN, 1600, 1f, 8f))
        assertNull(RecordingZoomPlan.create(2.8f, 1600, 1f, 1f))
        assertNull(RecordingZoomPlan.create(2.8f, 1600, 2f, 1f))
    }

    @Test
    fun superzoomHasBoundedNativeProgressAndDuration() {
        val plan = requireNotNull(RecordingZoomPlan.create(2.8f, 1600, 0.5f, 8f))
        assertEquals(1600L, plan.durationMillis)
        assertEquals(1f, plan.factor(-1f), 0.0001f)
        assertEquals(1.67332f, plan.factor(0.5f), 0.0001f)
        assertEquals(2.8f, plan.factor(2f), 0.0001f)
    }

    @Test
    fun targetAndBaselineStayInsideTheDeviceRange() {
        val limited = requireNotNull(RecordingZoomPlan.create(2.8f, 1600, 1f, 2f))
        assertEquals(2f, limited.target, 0f)
        val baseline = requireNotNull(RecordingZoomPlan.create(8f, Long.MAX_VALUE, 1.5f, 4f))
        assertEquals(1.5f, baseline.start, 0f)
        assertEquals(4f, baseline.factor(1f), 0f)
        assertEquals(600_000L, baseline.durationMillis)
    }
}
