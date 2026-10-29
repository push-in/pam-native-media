package dev.pam.media

import android.Manifest
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.content.Context
import android.content.pm.PackageManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.views.NativeViewFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraViewFactory(@Suppress("UNUSED_PARAMETER") context: Context) : NativeViewFactory {
    override fun create(context: Context, emit: (ByteArray) -> Unit): View = CameraHost(context).apply { emitter = emit }
    override fun update(view: View, properties: Map<String, WireValue>) = (view as CameraHost).update(properties)
    override fun release(view: View) = (view as CameraHost).release()
}

private class CameraHost(context: Context) : FrameLayout(context) {
    var emitter: ((ByteArray) -> Unit)? = null
    private val preview = PreviewView(context).apply {
        // SurfaceView preview (react-native-vision-camera's default): the
        // camera buffers reach the display compositor with their own colour
        // space and range. The TextureView path re-samples them through GL
        // and rendered a flatter, darker preview than the captured photo.
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var facing = 1L
    private var mode = 1L
    private var flashMode = 1L
    private var enabled = true
    private var audioEnabled = true
    private var maxDurationSeconds = 60L
    private var captureRevision = 0L
    private var recordRevision = 0L
    private var stopRevision = 0L
    private var zoomTarget = 1f
    private var zoomDurationMillis = 0L
    private val recordingZoom = CameraRecordingZoom()
    private var recordingRevision = 0L
    private var recordingStopping = false
    private var stopTask: Runnable? = null
    private var released = false
    private var bindRevision = 0L
    private var requestedFacing = 1L
    private var tapToFocus = false
    private val focusRequest = CameraFocusRequest()
    private var pinchZoom = false
    private var doubleTapFlip = false
    private var doubleTapEvents = false
    private var maxZoom = CameraZoomRange.DEFAULT_MAX_ZOOM
    private var zoomResetRevision: Long? = null
    private val pinch = PinchZoomTracker()
    private var scaleDetector: ScaleGestureDetector? = null
    private var tapDetector: GestureDetector? = null

    init { addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); post(::bind) }

    // Touches pass through unless a gesture is opted in; detectors exist only while enabled.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val scale = scaleDetector
        val taps = tapDetector
        if (released || !enabled || (scale == null && taps == null)) return false
        scale?.onTouchEvent(event)
        taps?.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinch.end()
        return true
    }

    // SurfaceView bypasses the parent's rounded drawing clip. Select TextureView
    // only for clipped/rounded hosts, including a radius styled on an ancestor.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updatePreviewMode()
    }

    private fun updatePreviewMode() {
        val outline = Outline()
        var host: View? = this
        var rounded = false
        while (host != null) {
            outline.setEmpty()
            host.outlineProvider?.getOutline(host, outline)
            if (host.clipToOutline || outline.radius > 0f || hasRoundedBackground(host.background)) { rounded = true; break }
            host = host.parent as? View
        }
        val next = if (rounded) PreviewView.ImplementationMode.COMPATIBLE else PreviewView.ImplementationMode.PERFORMANCE
        if (preview.implementationMode == next) return
        preview.implementationMode = next
        // Request a new surface without rebinding use cases or ending a recording.
        previewUseCase?.setSurfaceProvider(null)
        previewUseCase?.setSurfaceProvider(preview.surfaceProvider)
    }

    private fun hasRoundedBackground(drawable: Drawable?): Boolean = when (drawable) {
        is GradientDrawable -> drawable.cornerRadius > 0f || drawable.cornerRadii?.any { it > 0f } == true
        is LayerDrawable -> (0 until drawable.numberOfLayers).any { hasRoundedBackground(drawable.getDrawable(it)) }
        is InsetDrawable -> hasRoundedBackground(drawable.drawable)
        else -> false
    }

    private fun clearTapDetector() {
        // GestureDetector holds a delayed single-tap confirmation. Cancel it on
        // disable/release so an old touch cannot focus a newly enabled camera.
        val detector = tapDetector ?: return
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        detector.onTouchEvent(cancel)
        cancel.recycle()
        tapDetector = null
    }

    fun update(v: Map<String, WireValue>) {
        if (released) return
        val nextFacing = v.integer("facing", 1)
        val nextMode = v.integer("mode", 1).coerceIn(1, 3)
        val nextCapture = v.integer("captureRevision", 0)
        val nextRecord = v.integer("recordRevision", 0)
        val nextStop = v.integer("stopRevision", 0)
        val wasEnabled = enabled
        enabled = v.flag("enabled", true); audioEnabled = v.flag("audioEnabled", true)
        flashMode = v.integer("flashMode", 1); maxDurationSeconds = v.integer("maxDurationSeconds", 60).coerceIn(1, 600)
        zoomTarget = v.decimal("zoomTarget", 1.0).toFloat().takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        zoomDurationMillis = v.integer("zoomDurationMillis", 0).coerceIn(0, 600_000)
        tapToFocus = v.flag("tapToFocus", false)
        pinchZoom = v.flag("pinchZoom", false)
        doubleTapFlip = v.flag("doubleTapFlip", false)
        doubleTapEvents = v.flag("doubleTapEvents", false)
        maxZoom = v.decimal("maxZoom", CameraZoomRange.DEFAULT_MAX_ZOOM.toDouble()).toFloat()
            .takeIf { it.isFinite() && it > 0f } ?: CameraZoomRange.DEFAULT_MAX_ZOOM
        val nextZoomReset = v.integer("zoomResetRevision", 0)
        val previousZoomReset = zoomResetRevision
        zoomResetRevision = nextZoomReset
        updatePreviewMode()
        updateGestures()
        if (!enabled) stopRecording()
        // A native double-tap flip owns `facing` until the host requests a different lens.
        if (nextFacing != requestedFacing) { requestedFacing = nextFacing; if (nextFacing != facing) { facing = nextFacing; bind() } }
        if (nextMode != mode) { mode = nextMode; bind() }
        // Hosts gate the camera on its permission (`enabled` false until granted):
        // the init bind() was skipped, so enabling must bind; disabling releases
        // the device so nothing keeps the camera open unseen.
        if (enabled && !wasEnabled && camera == null) bind()
        if (!enabled && wasEnabled && recording == null) unbind()
        imageCapture?.flashMode = nativeFlash(flashMode)
        camera?.cameraControl?.enableTorch(recording != null && flashMode == 2L)
        if (nextCapture > captureRevision) { captureRevision = nextCapture; takePhoto() }
        if (nextRecord > recordRevision) { recordRevision = nextRecord; startRecording() }
        if (nextStop > stopRevision) { stopRevision = nextStop; stopRecording() }
        if (focusRequest.consume(v.integer("focusRevision", 0))) {
            val point = CameraFocusPoint.normalized(v.decimal("focusX", 0.5), v.decimal("focusY", 0.5), preview.width, preview.height)
            if (point == null) send(6, "Focus requires valid coordinates and a laid out preview")
            else focusAt(point.first, point.second)
        }
        if (previousZoomReset != null && nextZoomReset != previousZoomReset) resetZoom()
    }

    private fun zoomRange(active: Camera): CameraZoomRange? =
        active.cameraInfo.zoomState.value?.let { CameraZoomRange.create(it.minZoomRatio, it.maxZoomRatio, maxZoom) }

    private fun resetZoom() {
        recordingZoom.cancel()
        pinch.end()
        val active = camera ?: return
        val range = zoomRange(active) ?: return
        active.cameraControl.setZoomRatio(range.resetValue())
    }

    private fun updateGestures() {
        val active = enabled && !released
        if (!active || !pinchZoom) { scaleDetector = null; pinch.end() }
        else if (scaleDetector == null) scaleDetector = ScaleGestureDetector(context, PinchListener()).apply { isQuickScaleEnabled = false }
        if (!active || !(doubleTapFlip || doubleTapEvents || tapToFocus)) clearTapDetector()
        else if (tapDetector == null) tapDetector = GestureDetector(context, DoubleTapListener())
    }

    private inner class PinchListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            val active = camera ?: return false
            val current = active.cameraInfo.zoomState.value?.zoomRatio ?: return false
            // The user's pinch overrides a running recording zoom ramp and keeps its ratio afterwards.
            recordingZoom.cancel()
            pinch.begin(current)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val active = camera ?: return false
            val range = zoomRange(active) ?: return false
            pinch.scale(detector.scaleFactor, range)?.let(active.cameraControl::setZoomRatio)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = pinch.end()
    }

    private inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!tapToFocus || pinch.active) return false
            focusAt(event.x, event.y)
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (!(doubleTapFlip || doubleTapEvents)) return false
            if (released || !enabled) return false
            // Rebinding would end the clip, so an active recording keeps its lens.
            val flip = doubleTapFlip && recording == null
            // The new lens reports its range in the Ready that follows; the event leaves it at 0.
            val zoom = if (flip) null else camera?.let(::zoomRange)
            if (flip) { facing = if (facing == 2L) 1L else 2L; bind() }
            send(7, zoom = zoom)
            return true
        }
    }

    private fun unbind() {
        ++bindRevision
        recordingZoom.cancel()
        pinch.end()
        provider?.unbindAll()
        camera = null
        previewUseCase = null
        imageCapture = null
        videoCapture = null
    }

    private fun focusAt(x: Float, y: Float) {
        if (released || !enabled) return
        val active = camera ?: run { send(6, "Camera is not ready"); return }
        if (preview.width <= 0 || preview.height <= 0) return
        val point = preview.meteringPointFactory.createPoint(x.coerceIn(0f, preview.width.toFloat()), y.coerceIn(0f, preview.height.toFloat()))
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        // Fixed-focus lenses may still support exposure metering. CameraX handles
        // rotation, front-camera mirroring and FILL_CENTER crop via the factory.
        if (!active.cameraInfo.isFocusMeteringSupported(action)) return
        val revision = bindRevision
        runCatching {
            val future = active.cameraControl.startFocusAndMetering(action)
            future.addListener({
                if (released || !enabled || revision != bindRevision || active !== camera) return@addListener
                runCatching { future.get() }.onFailure {
                    if (it.cause !is androidx.camera.core.CameraControl.OperationCanceledException) send(6, it.cause?.message ?: it.message.orEmpty())
                }
            }, ContextCompat.getMainExecutor(context))
        }.onFailure { send(6, it.message.orEmpty()) }
    }

    private fun bind() {
        if (!enabled || released) return
        stopRecording()
        pinch.end()
        val revision = ++bindRevision
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { send(5, "Camera permission is required"); return }
        val owner = context as? LifecycleOwner ?: run { send(6, "Camera host has no lifecycle"); return }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ runCatching {
            if (released || revision != bindRevision || !enabled) return@addListener
            val p = future.get(); provider = p; p.unbindAll()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider; this.previewUseCase = it }
            val selector = if (facing == 2L) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (mode == 2L) {
                imageCapture = null
                videoCapture = videoUseCase()
                camera = p.bindToLifecycle(owner, selector, previewUseCase, videoCapture)
            } else if (mode == 3L) {
                // Photo + hold-to-record on one session; devices that reject the
                // combined streams keep the photo pipeline (recording then fails loudly).
                val photo = ImageCapture.Builder().setFlashMode(nativeFlash(flashMode)).build()
                val video = videoUseCase()
                imageCapture = photo
                camera = runCatching {
                    videoCapture = video
                    p.bindToLifecycle(owner, selector, previewUseCase, photo, video)
                }.getOrElse {
                    p.unbindAll()
                    videoCapture = null
                    p.bindToLifecycle(owner, selector, previewUseCase, photo)
                }
            } else {
                videoCapture = null
                imageCapture = ImageCapture.Builder().setFlashMode(nativeFlash(flashMode)).build()
                camera = p.bindToLifecycle(owner, selector, previewUseCase, imageCapture)
            }
            send(1, zoom = camera?.let(::zoomRange) ?: CameraZoomRange.create(1f, 1f, maxZoom))
        }.onFailure { send(6, it.message.orEmpty()) } }, ContextCompat.getMainExecutor(context))
    }

    private fun videoUseCase(): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            Quality.FHD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
        )
        return VideoCapture.withOutput(Recorder.Builder().setQualitySelector(qualitySelector).build())
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            send(6, "Camera is not ready")
            return
        }
        val file = captureFile("jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                send(2, path=capturePath(file), mime="image/jpeg")
            }

            override fun onError(error: ImageCaptureException) {
                send(6, error.message.orEmpty())
            }
        })
    }

    private fun startRecording() {
        if (recording != null || !enabled || released) return
        val output = videoCapture?.output ?: run {
            send(6, "Camera is not ready")
            return
        }
        val file = captureFile("mp4")
        var pending: PendingRecording = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (audioEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recordingStopping = false
        val revision = ++recordingRevision
        val recordingCamera = camera
        val target = zoomTarget
        val zoomMillis = zoomDurationMillis
        val durationMillis = maxDurationSeconds * 1000
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (released || revision != recordingRevision) return@start
            when (event) {
                is VideoRecordEvent.Start -> {
                    if (recordingStopping) { recording?.stop(); return@start }
                    recordingCamera?.cameraControl?.enableTorch(flashMode == 2L)
                    recordingZoom.start(recordingCamera, target, zoomMillis)
                    stopTask = Runnable { if (revision == recordingRevision) stopRecording() }.also { postDelayed(it, durationMillis) }
                    send(3)
                }
                is VideoRecordEvent.Finalize -> {
                    cancelStopTask()
                    recordingZoom.reset()
                    recordingCamera?.cameraControl?.enableTorch(false)
                    recording = null
                    if (event.hasError()) send(6, event.cause?.message ?: "Video capture failed")
                    else send(4, path=capturePath(file), mime="video/mp4", duration=event.recordingStats.recordedDurationNanos/1_000_000)
                }
            }
        }
    }
    private fun stopRecording() { recordingStopping = true; cancelStopTask(); recordingZoom.reset(); recording?.stop() }
    private fun cancelStopTask() { stopTask?.let(::removeCallbacks); stopTask = null }
    private fun captureFile(extension: String): File {
        val directory = File(context.filesDir, "pam-files/captures").apply { mkdirs() }
        return File(directory, "pam-camera-${System.currentTimeMillis()}.$extension")
    }
    private fun capturePath(file: File) = "captures/${file.name}"
    private fun nativeFlash(value: Long) = when(value){2L->ImageCapture.FLASH_MODE_ON;3L->ImageCapture.FLASH_MODE_AUTO;else->ImageCapture.FLASH_MODE_OFF}
    // Ready (1) and DoubleTap (7) carry the lens and effective zoom range; other events report 0 for the range.
    private fun send(event:Long,message:String="",path:String="",mime:String="",duration:Long=0,zoom:CameraZoomRange?=null){val lens=facing;val hasFlash=zoom != null && camera?.cameraInfo?.hasFlashUnit() == true;post{emitter?.invoke(WireMap.encode(mapOf("event" to WireValue.Integer(event),"message" to WireValue.Text(message),"path" to WireValue.Text(path),"mimeType" to WireValue.Text(mime),"width" to WireValue.Integer(0),"height" to WireValue.Integer(0),"durationMillis" to WireValue.Integer(duration),"facing" to WireValue.Integer(lens),"hasFlash" to WireValue.Flag(hasFlash),"minZoom" to WireValue.Decimal((zoom?.minimum ?: 0f).toDouble()),"maxZoom" to WireValue.Decimal((zoom?.maximum ?: 0f).toDouble()))))}}
    fun release(){released=true;scaleDetector=null;clearTapDetector();pinch.end();++bindRevision;++recordingRevision;cancelStopTask();recordingZoom.reset();emitter=null;recording?.close();recording=null;provider?.unbindAll();provider=null;camera=null;previewUseCase=null;executor.shutdownNow()}
    private fun Map<String,WireValue>.integer(key:String,fallback:Long)=(get(key)as?WireValue.Integer)?.value?:fallback
    private fun Map<String,WireValue>.flag(key:String,fallback:Boolean)=(get(key)as?WireValue.Flag)?.value?:fallback
    private fun Map<String,WireValue>.decimal(key:String,fallback:Double)=when(val value=get(key)){is WireValue.Decimal->value.value;is WireValue.Integer->value.value.toDouble();else->fallback}
}
