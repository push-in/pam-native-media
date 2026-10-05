package dev.pam.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray

internal data class ImageProbe(
    val width: Int,
    val height: Int,
    val storedWidth: Int,
    val storedHeight: Int,
    val orientation: Int,
    val mimeType: String,
    val bytes: Long,
)

internal data class ImageOutput(val width: Int, val height: Int, val bytes: Long, val mimeType: String)

internal sealed interface ImageOperation {
    data class Resize(val width: Int, val height: Int, val mode: Int) : ImageOperation
    data class Crop(val x: Int, val y: Int, val width: Int, val height: Int) : ImageOperation
    data class Rotate(val degrees: Int) : ImageOperation
    data class Flip(val vertical: Boolean) : ImageOperation

    companion object {
        fun parse(json: String): List<ImageOperation> {
            val array = JSONArray(json)
            require(array.length() <= 32) { "Too many image operations" }
            return List(array.length()) { index ->
                val op = array.getJSONObject(index)
                when (op.getString("op")) {
                    "resize" -> Resize(op.optInt("width").coerceIn(0, 16_384), op.optInt("height").coerceIn(0, 16_384), op.optInt("mode", 1))
                    "crop" -> Crop(op.getInt("x"), op.getInt("y"), op.getInt("width"), op.getInt("height"))
                    "rotate" -> Rotate(op.getInt("degrees").also { require(it % 90 == 0) { "Rotation must be a multiple of 90" } })
                    "flip" -> Flip(op.optInt("direction", 1) == 2)
                    else -> error("Unknown image operation")
                }
            }
        }
    }
}

/**
 * Decode → EXIF orientation → operations in call order → encode. The decoder
 * subsamples by the largest power of two that still yields at least the final
 * resolution, and caps decoded pixels so huge photos cannot exhaust memory.
 */
internal object ImagePipeline {
    private const val MAX_DECODED_PIXELS = 48_000_000L

    fun probe(file: File): ImageProbe {
        val bounds = bounds(file)
        val orientation = orientation(file)
        val swap = swaps(orientation)
        return ImageProbe(
            width = if (swap) bounds.outHeight else bounds.outWidth,
            height = if (swap) bounds.outWidth else bounds.outHeight,
            storedWidth = bounds.outWidth,
            storedHeight = bounds.outHeight,
            orientation = orientation,
            mimeType = bounds.outMimeType ?: "application/octet-stream",
            bytes = file.length(),
        )
    }

    fun process(
        source: File,
        destination: File,
        operations: List<ImageOperation>,
        onlyScaleDown: Boolean,
        format: Int,
        quality: Int,
    ): ImageOutput {
        val probe = probe(source)
        val scale = requiredScale(probe.width, probe.height, operations, onlyScaleDown)
        var sample = 1
        while (scale * sample * 2 <= 1.0) sample *= 2
        while ((probe.storedWidth.toLong() / sample) * (probe.storedHeight.toLong() / sample) > MAX_DECODED_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Image decode failed")
        var bitmap = transform(decoded, exifMatrix(probe.orientation))
        var logicalWidth = probe.width.toDouble()
        var ratio = bitmap.width / logicalWidth
        var logicalHeight = probe.height.toDouble()
        for (operation in operations) {
            when (operation) {
                is ImageOperation.Crop -> {
                    val x = (operation.x * ratio).roundToInt().coerceIn(0, bitmap.width - 1)
                    val y = (operation.y * ratio).roundToInt().coerceIn(0, bitmap.height - 1)
                    val w = (operation.width * ratio).roundToInt().coerceIn(1, bitmap.width - x)
                    val h = (operation.height * ratio).roundToInt().coerceIn(1, bitmap.height - y)
                    bitmap = replace(bitmap, Bitmap.createBitmap(bitmap, x, y, w, h))
                    logicalWidth = w / ratio
                    logicalHeight = h / ratio
                }
                is ImageOperation.Resize -> {
                    val plan = resizePlan(logicalWidth, logicalHeight, operation, onlyScaleDown)
                    if (plan.scaledWidth != bitmap.width || plan.scaledHeight != bitmap.height) {
                        bitmap = replace(bitmap, Bitmap.createScaledBitmap(bitmap, plan.scaledWidth, plan.scaledHeight, true))
                    }
                    if (plan.cropWidth < bitmap.width || plan.cropHeight < bitmap.height) {
                        val x = (bitmap.width - plan.cropWidth) / 2
                        val y = (bitmap.height - plan.cropHeight) / 2
                        bitmap = replace(bitmap, Bitmap.createBitmap(bitmap, x, y, plan.cropWidth, plan.cropHeight))
                    }
                    ratio = 1.0
                    logicalWidth = bitmap.width.toDouble()
                    logicalHeight = bitmap.height.toDouble()
                }
                is ImageOperation.Rotate -> {
                    bitmap = transform(bitmap, Matrix().apply { postRotate(operation.degrees.toFloat()) })
                    if (operation.degrees % 180 != 0) logicalWidth = logicalHeight.also { logicalHeight = logicalWidth }
                }
                is ImageOperation.Flip -> bitmap = transform(
                    bitmap,
                    Matrix().apply { if (operation.vertical) postScale(1f, -1f) else postScale(-1f, 1f) },
                )
            }
        }
        try {
            return encode(bitmap, destination, format, quality)
        } finally {
            bitmap.recycle()
        }
    }

    /** Writes [bitmap] atomically; JPEG output is flattened onto white. */
    fun encode(bitmap: Bitmap, destination: File, format: Int, quality: Int): ImageOutput {
        destination.parentFile?.mkdirs()
        val staged = File(destination.parentFile, ".${destination.name}.part")
        val opaque = if (format == 1 && bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { flat ->
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        } else {
            bitmap
        }
        try {
            staged.outputStream().use { output ->
                check(opaque.compress(compressFormat(format), quality.coerceIn(1, 100), output)) { "Image encoding failed" }
            }
        } catch (error: Throwable) {
            staged.delete()
            throw error
        } finally {
            if (opaque !== bitmap) opaque.recycle()
        }
        if (!staged.renameTo(destination)) {
            destination.delete()
            check(staged.renameTo(destination)) { "Unable to move the image into place" }
        }
        return ImageOutput(bitmap.width, bitmap.height, destination.length(), mimeType(format))
    }

    fun orientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL).takeIf { it in 1..8 } ?: 1

    fun exifMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            2 -> postScale(-1f, 1f)
            3 -> postRotate(180f)
            4 -> postScale(1f, -1f)
            5 -> { postRotate(90f); postScale(-1f, 1f) }
            6 -> postRotate(90f)
            7 -> { postRotate(270f); postScale(-1f, 1f) }
            8 -> postRotate(270f)
        }
    }

    fun mimeType(format: Int) = when (format) {
        2 -> "image/png"
        3 -> "image/webp"
        else -> "image/jpeg"
    }

    internal data class ResizePlan(val scaledWidth: Int, val scaledHeight: Int, val cropWidth: Int, val cropHeight: Int)

    /** Pure geometry of one resize, exposed for unit tests. */
    internal fun resizePlan(width: Double, height: Double, operation: ImageOperation.Resize, onlyScaleDown: Boolean): ResizePlan {
        val boxW = operation.width.toDouble()
        val boxH = operation.height.toDouble()
        val mode = if (operation.mode == 2 && (boxW == 0.0 || boxH == 0.0)) 1 else operation.mode
        return when (mode) {
            3 -> {
                var w = if (boxW > 0) boxW else width
                var h = if (boxH > 0) boxH else height
                if (onlyScaleDown) {
                    w = min(w, width)
                    h = min(h, height)
                }
                val sw = px(w)
                val sh = px(h)
                ResizePlan(sw, sh, sw, sh)
            }
            2 -> {
                var s = max(boxW / width, boxH / height)
                if (onlyScaleDown) s = min(s, 1.0)
                val sw = px(width * s)
                val sh = px(height * s)
                // Keep the box aspect ratio even when onlyScaleDown prevents filling it.
                val aspect = boxW / boxH
                ResizePlan(sw, sh, minOf(sw, px(boxW), px(sh * aspect)), minOf(sh, px(boxH), px(sw / aspect)))
            }
            else -> {
                var s = min(if (boxW > 0) boxW / width else Double.MAX_VALUE, if (boxH > 0) boxH / height else Double.MAX_VALUE)
                if (onlyScaleDown) s = min(s, 1.0)
                val sw = px(width * s)
                val sh = px(height * s)
                ResizePlan(sw, sh, sw, sh)
            }
        }
    }

    /** Linear scale (relative to the oriented source) the decoded bitmap must keep. */
    internal fun requiredScale(width: Int, height: Int, operations: List<ImageOperation>, onlyScaleDown: Boolean): Double {
        var w = width.toDouble()
        var h = height.toDouble()
        for (operation in operations) {
            when (operation) {
                is ImageOperation.Crop -> {
                    w = min(operation.width.toDouble(), w)
                    h = min(operation.height.toDouble(), h)
                }
                is ImageOperation.Resize -> {
                    // Later operations work on the resized bitmap, so only the first resize bounds decoding.
                    val plan = resizePlan(w, h, operation, onlyScaleDown)
                    return min(1.0, max(plan.scaledWidth / w, plan.scaledHeight / h))
                }
                is ImageOperation.Rotate -> if (operation.degrees % 180 != 0) w = h.also { h = w }
                is ImageOperation.Flip -> Unit
            }
        }
        return 1.0
    }

    private fun bounds(file: File): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        return bounds
    }

    private fun swaps(orientation: Int) = orientation in 5..8

    private fun transform(bitmap: Bitmap, matrix: Matrix): Bitmap =
        if (matrix.isIdentity) bitmap else replace(bitmap, Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))

    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (new !== old) old.recycle()
        return new
    }

    private fun px(value: Double) = max(1, value.roundToInt())

    @Suppress("DEPRECATION")
    private fun compressFormat(format: Int): Bitmap.CompressFormat = when (format) {
        2 -> Bitmap.CompressFormat.PNG
        3 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
}

