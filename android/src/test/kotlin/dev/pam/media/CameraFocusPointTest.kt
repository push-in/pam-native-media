package dev.pam.media

import org.junit.Assert.*
import org.junit.Test

class CameraFocusPointTest {
    @Test fun coordinatesStayInPreviewSpaceForCameraX() {
        assertEquals(Pair(270f, 480f), CameraFocusPoint.normalized(0.25, 0.25, 1080, 1920))
        assertEquals(Pair(0f, 1920f), CameraFocusPoint.normalized(0.0, 1.0, 1080, 1920))
        assertEquals(Pair(1920f, 0f), CameraFocusPoint.normalized(1.0, 0.0, 1920, 1080))
    }
    @Test fun invalidCoordinatesAndUnlaidOutPreviewsAreRejected() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertNull(CameraFocusPoint.normalized(value, 0.5, 100, 100))
            assertNull(CameraFocusPoint.normalized(0.5, value, 100, 100))
        }
        assertNull(CameraFocusPoint.normalized(0.5, 0.5, 0, 100))
        assertNull(CameraFocusPoint.normalized(0.5, 0.5, 100, -1))
    }
    @Test fun repeatedRendersDoNotRefocus() {
        val request = CameraFocusRequest()
        assertFalse(request.consume(0))
        assertTrue(request.consume(1))
        assertFalse(request.consume(1))
        assertFalse(request.consume(0))
        assertTrue(request.consume(3))
        assertFalse(request.consume(2))
    }
}
