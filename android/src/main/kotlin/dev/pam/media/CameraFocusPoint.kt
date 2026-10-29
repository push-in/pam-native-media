package dev.pam.media

/** Preview-local pixels; CameraX performs the sensor/crop/mirror transform. */
internal object CameraFocusPoint {
    fun normalized(x: Double, y: Double, width: Int, height: Int): Pair<Float, Float>? {
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0 || width <= 0 || height <= 0) return null
        return Pair((x * width).toFloat(), (y * height).toFloat())
    }
}

/** Commands run once, including on the first render when revision is positive. */
internal class CameraFocusRequest {
    private var revision = 0L
    fun consume(next: Long): Boolean {
        if (next <= revision) return false
        revision = next
        return true
    }
}
