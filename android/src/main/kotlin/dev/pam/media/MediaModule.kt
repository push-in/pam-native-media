package dev.pam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.webkit.MimeTypeMap
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

class MediaModule(context: Context) : NativeModule, AutoCloseable {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "pam-files").apply { mkdirs() }.canonicalFile
    private val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "pam-media") }
    private val transcodes = ConcurrentHashMap<Long, TranscodeJob>()
    private val nextTask = AtomicLong(1)

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        val values = runCatching { WireMap.decode(payload) }.getOrElse {
            completion.failure(it)
            return
        }
        when (method) {
            "transcodeNext" -> runCatching { transcodes[values.integer("task")] ?: error("Transcode task not found") }
                .onSuccess { it.next(completion) }
                .onFailure { completion.failure(it) }
            "transcodeCancel" -> {
                transcodes[values.integer("task")]?.cancelled?.set(true)
                completion.success(emptyMap())
            }
            "transcodeStart" -> runCatching { startTranscode(values) }
                .onSuccess { completion.success(it) }
                .onFailure { completion.failure(it) }
            else -> executor.execute {
                runCatching {
                    when (method) {
                        "probe" -> probe(file(values.text("path"), true))
                        "thumbnail" -> thumbnail(values)
                        "imageProbe" -> imageProbe(file(values.text("path"), true))
                        "imageProcess" -> imageProcess(values)
                        "thumbnails" -> thumbnails(values.text("items"))
                        else -> error("Unknown method: $method")
                    }
                }.onSuccess { completion.success(it) }.onFailure { completion.failure(it) }
            }
        }
    }

    private fun probe(source: File): Map<String, WireValue> {
        val mime = mimeOf(source)
        var width = 0
        var height = 0
        var duration = 0L
        var orientation = 0
        val kind = when {
            mime.startsWith("image/") -> {
                val info = ImagePipeline.probe(source)
                width = info.width
                height = info.height
                orientation = when (info.orientation) {
                    3, 4 -> 180
                    5, 6 -> 90
                    7, 8 -> 270
                    else -> 0
                }
                1
            }
            mime.startsWith("audio/") || mime.startsWith("video/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(source.path)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } finally {
                    runCatching { retriever.release() }
                }
                if (mime.startsWith("video/")) 3 else 2
            }
            else -> 4
        }
        return mapOf(
            "kind" to WireValue.Integer(kind.toLong()),
            "mimeType" to WireValue.Text(mime),
            "bytes" to WireValue.Integer(source.length()),
            "width" to WireValue.Integer(width.toLong()),
            "height" to WireValue.Integer(height.toLong()),
            "durationMillis" to WireValue.Integer(duration),
            "orientationDegrees" to WireValue.Integer(orientation.toLong()),
        )
    }

    private fun thumbnail(values: Map<String, WireValue>): Map<String, WireValue> {
        val destination = values.text("destination")
        renderThumbnail(
            values.text("source"),
            destination,
            values.integer("maxWidth").toInt(),
            values.integer("maxHeight").toInt(),
            values.integer("format").toInt(),
            values.integer("quality").toInt(),
            values.integer("timeMillis"),
        )
        return mapOf("path" to WireValue.Text(destination))
    }

    private fun thumbnails(items: String): Map<String, WireValue> {
        val requests = JSONArray(items)
        require(requests.length() in 1..100) { "A thumbnail batch needs between 1 and 100 requests" }
        val results = JSONArray()
        for (index in 0 until requests.length()) {
            val request = requests.getJSONObject(index)
            val row = runCatching {
                val output = renderThumbnail(
                    request.getString("source"),
                    request.getString("destination"),
                    request.getInt("maxWidth"),
                    request.getInt("maxHeight"),
                    request.optInt("format", 1),
                    request.optInt("quality", 80),
                    request.optLong("timeMillis", 0),
                )
                JSONObject().put("path", request.getString("destination")).put("width", output.width).put("height", output.height)
            }.getOrElse { error -> JSONObject().put("error", error.message ?: "Thumbnail failed") }
            results.put(row)
        }
        return mapOf("results" to WireValue.Text(results.toString()))
    }

    private fun renderThumbnail(source: String, destinationPath: String, maxWidth: Int, maxHeight: Int, format: Int, quality: Int, timeMillis: Long): ImageOutput {
        require(maxWidth in 1..8192 && maxHeight in 1..8192) { "Thumbnail dimensions must be between 1 and 8192" }
        val destination = file(destinationPath, false)
        val remote = source.startsWith("https://")
        val local = if (remote) null else file(source, true)
        val mime = if (remote) "video/*" else mimeOf(local!!)
        if (!mime.startsWith("video/")) {
            return ImagePipeline.process(local!!, destination, listOf(ImageOperation.Resize(maxWidth, maxHeight, 1)), true, format, quality)
        }
        val frame = videoFrame(source, local, timeMillis, maxWidth, maxHeight)
        val scale = minOf(maxWidth.toFloat() / frame.width, maxHeight.toFloat() / frame.height, 1f)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, max(1, (frame.width * scale).toInt()), max(1, (frame.height * scale).toInt()), true)
        } else {
            frame
        }
        try {
            return ImagePipeline.encode(resized, destination, format, quality)
        } finally {
            if (resized !== frame) resized.recycle()
            frame.recycle()
        }
    }

    private fun videoFrame(source: String, local: File?, timeMillis: Long, maxWidth: Int, maxHeight: Int): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            if (local != null) retriever.setDataSource(local.path) else retriever.setDataSource(source, emptyMap())
            val time = timeMillis * 1000
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val (displayWidth, displayHeight) = if (rotation % 180 != 0) height to width else width to height
                if (displayWidth > maxWidth * 2 || displayHeight > maxHeight * 2) {
                    // Scaled frames are bounded in stored orientation; keep twice the target for quality.
                    val bound = max(maxWidth, maxHeight) * 2
                    retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, bound, bound)
                } else {
                    retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } else {
                retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return frame ?: error("Video frame unavailable")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun imageProbe(source: File): Map<String, WireValue> {
        val info = ImagePipeline.probe(source)
        return mapOf(
            "width" to WireValue.Integer(info.width.toLong()),
            "height" to WireValue.Integer(info.height.toLong()),
            "storedWidth" to WireValue.Integer(info.storedWidth.toLong()),
            "storedHeight" to WireValue.Integer(info.storedHeight.toLong()),
            "orientation" to WireValue.Integer(info.orientation.toLong()),
            "mimeType" to WireValue.Text(info.mimeType),
            "bytes" to WireValue.Integer(info.bytes),
        )
    }

    private fun imageProcess(values: Map<String, WireValue>): Map<String, WireValue> {
        val destination = values.text("destination")
        val output = ImagePipeline.process(
            source = file(values.text("source"), true),
            destination = file(destination, false),
            operations = ImageOperation.parse(values.text("operations")),
            onlyScaleDown = (values["onlyScaleDown"] as? WireValue.Flag)?.value ?: false,
            format = values.integer("format").toInt().also { require(it in 1..3) { "Unknown image format" } },
            quality = values.integer("quality").toInt(),
        )
        return mapOf(
            "path" to WireValue.Text(destination),
            "width" to WireValue.Integer(output.width.toLong()),
            "height" to WireValue.Integer(output.height.toLong()),
            "bytes" to WireValue.Integer(output.bytes),
            "mimeType" to WireValue.Text(output.mimeType),
        )
    }

    private fun startTranscode(values: Map<String, WireValue>): Map<String, WireValue> {
        val sourcePath = values.text("source")
        val destinationPath = values.text("destination")
        val source = file(sourcePath, true)
        val destination = file(destinationPath, false)
        val options = TranscodeOptions.parse(values.text("options"))
        val id = nextTask.getAndIncrement()
        val job = TranscodeJob()
        transcodes[id] = job
        executor.execute {
            runCatching {
                VideoTranscoder(context).transcode(source, destination, options, job.cancelled::get) { job.progress(it) }
            }.onSuccess { output ->
                job.finish(
                    mapOf(
                        "state" to WireValue.Integer(2),
                        "path" to WireValue.Text(destinationPath),
                        "mimeType" to WireValue.Text("video/mp4"),
                        "width" to WireValue.Integer(output.width.toLong()),
                        "height" to WireValue.Integer(output.height.toLong()),
                        "durationMillis" to WireValue.Integer(output.durationMillis),
                        "bytes" to WireValue.Integer(output.file.length()),
                        "bitrate" to WireValue.Integer(output.bitrate.toLong()),
                        "fastStart" to WireValue.Flag(output.fastStart),
                    ),
                )
            }.onFailure { error ->
                job.finish(mapOf("state" to WireValue.Integer(3), "message" to WireValue.Text(error.message ?: "Transcode failed")))
            }
        }
        return mapOf("task" to WireValue.Integer(id))
    }

    private fun mimeOf(file: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outMimeType
        ?: "application/octet-stream"

    private fun file(path: String, mustExist: Boolean): File {
        require(path.isNotEmpty() && path.length <= 1024 && !path.startsWith("/") && '\u0000' !in path) { "Media paths must be relative sandbox paths" }
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Path escapes app files" }
        if (mustExist) require(target.isFile) { "Media file does not exist" }
        return target
    }

    override fun close() {
        transcodes.values.forEach { it.cancelled.set(true) }
        executor.shutdown()
    }

    /** Conflated progress channel for one transcode, consumed by `transcodeNext` long-polls. */
    private inner class TranscodeJob {
        val cancelled = AtomicBoolean(false)
        private var pending: Map<String, WireValue>? = null
        private var terminal: Map<String, WireValue>? = null
        private var waiter: ModuleCompletion? = null
        private var lastPercent = -1

        fun progress(fraction: Double) {
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            val deliver = synchronized(this) {
                if (terminal != null || percent == lastPercent) return
                lastPercent = percent
                val payload = mapOf("state" to WireValue.Integer(1), "progress" to WireValue.Decimal(percent / 100.0))
                val current = waiter
                if (current == null) pending = payload else waiter = null
                current?.let { it to payload }
            }
            deliver?.let { (completion, payload) -> completion.success(payload) }
        }

        fun finish(payload: Map<String, WireValue>) {
            val current = synchronized(this) {
                terminal = payload
                pending = null
                waiter.also { waiter = null }
            }
            current?.let { complete(it, payload) }
        }

        fun next(completion: ModuleCompletion) {
            val ready = synchronized(this) {
                when {
                    pending != null -> pending.also { pending = null }
                    terminal != null -> terminal
                    waiter != null -> null.also { completion.failure(IllegalStateException("Transcode observation already pending")) }
                    else -> null.also { waiter = completion }
                }
            }
            ready?.let { complete(completion, it) }
        }

        private fun complete(completion: ModuleCompletion, payload: Map<String, WireValue>) {
            if ((payload["state"] as? WireValue.Integer)?.value != 1L) transcodes.values.remove(this)
            completion.success(payload)
        }
    }
}

private fun Map<String, WireValue>.text(key: String) = (get(key) as? WireValue.Text)?.value ?: error("$key is required")

private fun Map<String, WireValue>.integer(key: String) = (get(key) as? WireValue.Integer)?.value ?: error("$key is required")

private fun ModuleCompletion.success(values: Map<String, WireValue>) = complete(ModuleResultStatus.SUCCESS, WireMap.encode(values))

private fun ModuleCompletion.failure(error: Throwable) = complete(ModuleResultStatus.FAILURE, (error.message ?: "Media failure").toByteArray())
