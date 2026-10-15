package dev.pam.media

/** Effective zoom bounds: the camera's range capped by the `maxZoom` property. Pure, JVM-testable. */
internal data class CameraZoomRange(val minimum: Float, val maximum: Float) {
    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(minimum, maximum) else minimum

    /** `zoomResetRevision` target: 1x when the lens supports it. */
    fun resetValue(): Float = clamp(1f)

    /** Pinch zoom from the ratio at gesture begin and the scale accumulated since then. */
    fun pinch(startRatio: Float, cumulativeScale: Float): Float =
        if (!cumulativeScale.isFinite() || cumulativeScale <= 0f) clamp(startRatio) else clamp(startRatio * cumulativeScale)

    companion object {
        const val DEFAULT_MAX_ZOOM = 16f

        fun create(hardwareMinimum: Float, hardwareMaximum: Float, maxZoom: Float): CameraZoomRange {
            val minimum = if (hardwareMinimum.isFinite() && hardwareMinimum > 0f) hardwareMinimum else 1f
            val hardware = if (hardwareMaximum.isFinite() && hardwareMaximum >= minimum) hardwareMaximum else minimum
            val requested = if (maxZoom.isFinite() && maxZoom > 0f) maxZoom else DEFAULT_MAX_ZOOM
            return CameraZoomRange(minimum, requested.coerceIn(minimum, hardware))
        }
    }
}

/** Accumulates ScaleGestureDetector's per-event scale factors since the gesture began. */
internal class PinchZoomTracker {
    private var startRatio = 1f
    private var cumulativeScale = 1f
    var active = false
        private set

    fun begin(currentRatio: Float) {
        startRatio = if (currentRatio.isFinite() && currentRatio > 0f) currentRatio else 1f
        cumulativeScale = 1f
        active = true
    }

    /** Returns the zoom ratio to apply, or null when no gesture is active. */
    fun scale(scaleFactor: Float, range: CameraZoomRange): Float? {
        if (!active) return null
        if (scaleFactor.isFinite() && scaleFactor > 0f) {
            // Keep the accumulator inside the reachable range so reversing the pinch responds immediately.
            cumulativeScale = (cumulativeScale * scaleFactor).coerceIn(range.minimum / startRatio, range.maximum / startRatio)
        }
        return range.pinch(startRatio, cumulativeScale)
    }

    fun end() { active = false; cumulativeScale = 1f }
}
