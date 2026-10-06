package dev.pam.media

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.camera.core.Camera
import kotlin.math.pow

/** Optional zoom owned by one recording. All animation work stays on the UI thread. */
internal class CameraRecordingZoom {
    private var animator: ValueAnimator? = null
    private var camera: Camera? = null

    fun start(camera: Camera?, target: Float, durationMillis: Long) {
        reset()
        val active = camera ?: return
        val limits = active.cameraInfo.zoomState.value ?: return
        val plan = RecordingZoomPlan.create(target, durationMillis, limits.minZoomRatio, limits.maxZoomRatio) ?: return
        this.camera = active
        active.cameraControl.setZoomRatio(plan.start)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = plan.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener { active.cameraControl.setZoomRatio(plan.factor(it.animatedValue as Float)) }
            start()
        }
    }

    fun reset() {
        animator?.cancel()
        animator = null
        camera?.let { active ->
            active.cameraInfo.zoomState.value?.let { limits ->
                active.cameraControl.setZoomRatio(1f.coerceIn(limits.minZoomRatio, limits.maxZoomRatio))
            }
        }
        camera = null
    }
}

/** Same exponential factor progression as AVFoundation's native zoom ramp. */
internal data class RecordingZoomPlan(val start: Float, val target: Float, val durationMillis: Long) {
    fun factor(progress: Float): Float = (start * (target / start).pow(progress.coerceIn(0f, 1f))).coerceIn(start, target)

    companion object {
        fun create(target: Float, durationMillis: Long, minimum: Float, maximum: Float): RecordingZoomPlan? {
            if (!target.isFinite() || !minimum.isFinite() || !maximum.isFinite()
                || minimum <= 0f || maximum < minimum || durationMillis <= 0) return null
            val start = 1f.coerceIn(minimum, maximum)
            val end = target.coerceIn(start, maximum)
            return if (end > start) RecordingZoomPlan(start, end, durationMillis.coerceAtMost(600_000)) else null
        }
    }
}
