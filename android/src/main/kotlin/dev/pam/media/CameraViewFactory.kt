package dev.pam.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

class CameraViewFactory(@Suppress("UNUSED_PARAMETER") context: Context) : NativeViewFactory {
    override fun create(context: Context, emit: (ByteArray) -> Unit): View = CameraHost(context).apply { emitter = emit }
    override fun update(view: View, properties: Map<String, WireValue>) = (view as CameraHost).update(properties)
    override fun release(view: View) = (view as CameraHost).release()
}

private class CameraHost(context: Context) : FrameLayout(context) {
    var emitter: ((ByteArray) -> Unit)? = null
    private val preview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingStartedAt = 0L
    private var facing = 1L
    private var mode = 1L
    private var flashMode = 1L
    private var enabled = true
    private var audioEnabled = true
    private var maxDurationSeconds = 60L
    private var captureRevision = 0L
    private var recordRevision = 0L
    private var stopRevision = 0L

    init { addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)); post(::bind) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    fun update(v: Map<String, WireValue>) {
        val nextFacing = v.integer("facing", 1)
        val nextMode = v.integer("mode", 1).coerceIn(1, 2)
        val nextCapture = v.integer("captureRevision", 0)
        val nextRecord = v.integer("recordRevision", 0)
        val nextStop = v.integer("stopRevision", 0)
        enabled = v.flag("enabled", true); audioEnabled = v.flag("audioEnabled", true)
        flashMode = v.integer("flashMode", 1); maxDurationSeconds = v.integer("maxDurationSeconds", 60).coerceIn(1, 600)
        if (nextFacing != facing) { facing = nextFacing; bind() }
        if (nextMode != mode) { mode = nextMode; bind() }
        imageCapture?.flashMode = nativeFlash(flashMode)
        camera?.cameraControl?.enableTorch(recording != null && flashMode == 2L)
        if (nextCapture > captureRevision) { captureRevision = nextCapture; takePhoto() }
        if (nextRecord > recordRevision) { recordRevision = nextRecord; startRecording() }
        if (nextStop > stopRevision) { stopRevision = nextStop; stopRecording() }
    }

    private fun bind() {
        if (!enabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { send(5, "Camera permission is required"); return }
        val owner = context as? LifecycleOwner ?: run { send(6, "Camera host has no lifecycle"); return }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ runCatching {
            val p = future.get(); provider = p; p.unbindAll()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val selector = if (facing == 2L) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (mode == 2L) {
                imageCapture = null
                val qualitySelector = QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
                )
                val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
                videoCapture = VideoCapture.withOutput(recorder)
                camera = p.bindToLifecycle(owner, selector, previewUseCase, videoCapture)
            } else {
                videoCapture = null
                imageCapture = ImageCapture.Builder().setFlashMode(nativeFlash(flashMode)).build()
                camera = p.bindToLifecycle(owner, selector, previewUseCase, imageCapture)
            }
            send(1)
        }.onFailure { send(6, it.message.orEmpty()) } }, ContextCompat.getMainExecutor(context))
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
        if (recording != null) return
        val output = videoCapture?.output ?: run {
            send(6, "Camera is not ready")
            return
        }
        val file = captureFile("mp4")
        var pending: PendingRecording = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (audioEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recordingStartedAt = System.currentTimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { camera?.cameraControl?.enableTorch(flashMode == 2L); send(3) }
                is VideoRecordEvent.Finalize -> { camera?.cameraControl?.enableTorch(false); recording = null; if (event.hasError()) send(6, event.cause?.message ?: "Video capture failed") else send(4, path=capturePath(file), mime="video/mp4", duration=System.currentTimeMillis()-recordingStartedAt) }
            }
        }
        postDelayed({ if (recording != null) stopRecording() }, maxDurationSeconds * 1000)
    }
    private fun stopRecording() { recording?.stop() }
    private fun captureFile(extension: String): File {
        val directory = File(context.filesDir, "pam-files/captures").apply { mkdirs() }
        return File(directory, "pam-camera-${System.currentTimeMillis()}.$extension")
    }
    private fun capturePath(file: File) = "captures/${file.name}"
    private fun nativeFlash(value: Long) = when(value){2L->ImageCapture.FLASH_MODE_ON;3L->ImageCapture.FLASH_MODE_AUTO;else->ImageCapture.FLASH_MODE_OFF}
    private fun send(event:Long,message:String="",path:String="",mime:String="",duration:Long=0)=post{emitter?.invoke(WireMap.encode(mapOf("event" to WireValue.Integer(event),"message" to WireValue.Text(message),"path" to WireValue.Text(path),"mimeType" to WireValue.Text(mime),"width" to WireValue.Integer(0),"height" to WireValue.Integer(0),"durationMillis" to WireValue.Integer(duration))))}
    fun release(){recording?.close();recording=null;provider?.unbindAll();provider=null;camera=null;executor.shutdownNow()}
    private fun Map<String,WireValue>.integer(key:String,fallback:Long)=(get(key)as?WireValue.Integer)?.value?:fallback
    private fun Map<String,WireValue>.flag(key:String,fallback:Boolean)=(get(key)as?WireValue.Flag)?.value?:fallback
}
