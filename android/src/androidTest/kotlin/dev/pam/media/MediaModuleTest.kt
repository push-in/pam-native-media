package dev.pam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.DoubleConsumer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaModuleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.filesDir, "pam-files")
    private lateinit var module: MediaModule

    @Before
    fun setUp() {
        File(root, "media-test").deleteRecursively()
        File(root, "media-test").mkdirs()
        module = MediaModule(context)
    }

    @After
    fun tearDown() {
        module.close()
        File(root, "media-test").deleteRecursively()
    }

    @Test
    fun probeAppliesExifOrientation() {
        writeQuadrantJpeg("media-test/rot.jpg", 400, 300, ExifInterface.ORIENTATION_ROTATE_90)
        val (status, values) = call("imageProbe", mapOf("path" to WireValue.Text("media-test/rot.jpg")))
        assertEquals(ModuleResultStatus.SUCCESS, status)
        assertEquals(300L, values.int("width"))
        assertEquals(400L, values.int("height"))
        assertEquals(400L, values.int("storedWidth"))
        assertEquals(6L, values.int("orientation"))
        assertEquals("image/jpeg", (values["mimeType"] as WireValue.Text).value)
    }

    @Test
    fun pipelineOrientsResizesCropsRotatesAndFlips() {
        writeQuadrantJpeg("media-test/rot.jpg", 400, 300, ExifInterface.ORIENTATION_ROTATE_90)
        val (status, values) = call(
            "imageProcess",
            mapOf(
                "source" to WireValue.Text("media-test/rot.jpg"),
                "destination" to WireValue.Text("media-test/out/a.png"),
                "operations" to WireValue.Text(
                    JSONArray()
                        .put(JSONObject().put("op", "resize").put("width", 150).put("height", 150).put("mode", 1))
                        .put(JSONObject().put("op", "rotate").put("degrees", 270))
                        .put(JSONObject().put("op", "flip").put("direction", 1))
                        .toString(),
                ),
                "onlyScaleDown" to WireValue.Flag(true),
                "format" to WireValue.Integer(2),
                "quality" to WireValue.Integer(90),
            ),
        )
        assertEquals(String((values["message"] as? WireValue.Text)?.value?.toByteArray() ?: ByteArray(0)), ModuleResultStatus.SUCCESS, status)
        // 400x300 stored, EXIF 90° → 300x400 upright → contain 150 → 113x150 → rotate 270 → 150x113.
        assertEquals(150L, values.int("width"))
        assertEquals(113L, values.int("height"))
        val bitmap = BitmapFactory.decodeFile(File(root, "media-test/out/a.png").path)
        assertEquals(150, bitmap.width)
        // Stored top-left is red. EXIF 90° moves it to top-right; rotate 270 moves it to top-left; flip H to top-right.
        assertTrue("expected red at top-right", isRed(bitmap.getPixel(bitmap.width - 10, 10)))
        assertTrue("no temp file left behind", File(root, "media-test/out").list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun coverCropAndJpegFlattening() {
        writeQuadrantJpeg("media-test/plain.jpg", 400, 300, ExifInterface.ORIENTATION_NORMAL)
        val (_, cover) = call(
            "imageProcess",
            mapOf(
                "source" to WireValue.Text("media-test/plain.jpg"),
                "destination" to WireValue.Text("media-test/cover.webp"),
                "operations" to WireValue.Text("""[{"op":"crop","x":0,"y":0,"width":200,"height":150},{"op":"resize","width":100,"height":100,"mode":2}]"""),
                "onlyScaleDown" to WireValue.Flag(false),
                "format" to WireValue.Integer(3),
                "quality" to WireValue.Integer(80),
            ),
        )
        assertEquals(100L, cover.int("width"))
        assertEquals(100L, cover.int("height"))
        assertEquals("image/webp", (cover["mimeType"] as WireValue.Text).value)
        val bitmap = BitmapFactory.decodeFile(File(root, "media-test/cover.webp").path)
        assertTrue("crop kept the red top-left quadrant", isRed(bitmap.getPixel(50, 50)))
    }

    @Test
    fun batchThumbnailsKeepOrderAndReportErrors() {
        writeQuadrantJpeg("media-test/a.jpg", 1200, 900, ExifInterface.ORIENTATION_NORMAL)
        copyAsset("fixture.mp4", "media-test/clip.mp4")
        val items = JSONArray()
            .put(JSONObject().put("source", "media-test/a.jpg").put("destination", "media-test/t/a.jpg").put("maxWidth", 200).put("maxHeight", 200))
            .put(JSONObject().put("source", "media-test/clip.mp4").put("destination", "media-test/t/clip.jpg").put("maxWidth", 160).put("maxHeight", 160).put("timeMillis", 500))
            .put(JSONObject().put("source", "media-test/missing.mp4").put("destination", "media-test/t/x.jpg").put("maxWidth", 100).put("maxHeight", 100))
        val (status, values) = call("thumbnails", mapOf("items" to WireValue.Text(items.toString())))
        assertEquals(ModuleResultStatus.SUCCESS, status)
        val results = JSONArray((values["results"] as WireValue.Text).value)
        assertEquals(200, results.getJSONObject(0).getInt("width"))
        assertEquals(150, results.getJSONObject(0).getInt("height"))
        val clip = results.getJSONObject(1)
        assertTrue("video thumbnail fits", clip.getInt("width") <= 160 && clip.getInt("height") <= 160)
        assertTrue("rotated video stays portrait", clip.getInt("height") > clip.getInt("width"))
        assertTrue(results.getJSONObject(2).has("error"))
    }

    @Test
    fun transcodesThroughTheModuleWithLongPolledProgress() {
        copyAsset("fixture.mp4", "media-test/in.mp4")
        val (status, started) = call(
            "transcodeStart",
            mapOf(
                "source" to WireValue.Text("media-test/in.mp4"),
                "destination" to WireValue.Text("media-test/out.mp4"),
                "options" to WireValue.Text("""{"preset":1,"maxBitrate":600000,"fastStart":true,"audio":true}"""),
            ),
        )
        assertEquals(ModuleResultStatus.SUCCESS, status)
        val task = started.int("task")
        var result: Map<String, WireValue>
        val progress = mutableListOf<Double>()
        do {
            result = call("transcodeNext", mapOf("task" to WireValue.Integer(task)), timeoutSeconds = 120).second
            (result["progress"] as? WireValue.Decimal)?.value?.let(progress::add)
        } while (result.int("state") == 1L)
        assertEquals(result.toString(), 2L, result.int("state"))
        // 1280x720 rotated 90° → 720x1280 portrait → Compact480p bounds it to 480x854 (even).
        assertEquals(480L, result.int("width"))
        assertEquals(852L, result.int("height"))
        assertEquals(true, (result["fastStart"] as WireValue.Flag).value)
        val output = File(root, "media-test/out.mp4")
        assertTrue(Mp4FastStart.isFastStart(output))
        val formats = tracks(output)
        assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
        val audio = formats.first { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
        assertEquals(2, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertTrue(audio.getInteger(MediaFormat.KEY_SAMPLE_RATE) in setOf(44_100, 48_000))
        assertTrue("source untouched", File(root, "media-test/in.mp4").length() > 0)
    }

    @Test
    fun crossPluginEntryPointTranscodesAndHonorsCancellation() {
        copyAsset("fixture.mp4", "media-test/in.mp4")
        val output = File(root, "media-test/bridge.mp4")
        val json = JSONObject(
            MediaTranscoding.transcode(
                context,
                File(root, "media-test/in.mp4"),
                output,
                """{"preset":2}""",
                BooleanSupplier { false },
                DoubleConsumer { },
            ),
        )
        assertEquals(720, json.getInt("width"))
        assertEquals(output.length(), json.getLong("bytes"))
        val cancelled = runCatching {
            MediaTranscoding.transcode(context, File(root, "media-test/in.mp4"), File(root, "media-test/c.mp4"), """{"preset":2}""", BooleanSupplier { true }, DoubleConsumer { })
        }
        assertTrue(cancelled.exceptionOrNull() is TranscodeCancelledException)
        assertTrue(!File(root, "media-test/c.mp4").exists())
    }

    @Test
    fun rejectsSandboxEscapes() {
        val (status, _) = call("imageProbe", mapOf("path" to WireValue.Text("../shared_prefs/x.xml")))
        assertEquals(ModuleResultStatus.FAILURE, status)
    }

    private fun call(method: String, values: Map<String, WireValue>, timeoutSeconds: Long = 30): Pair<ModuleResultStatus, Map<String, WireValue>> {
        val latch = CountDownLatch(1)
        var status = ModuleResultStatus.FAILURE
        var payload = ByteArray(0)
        module.invoke(method, WireMap.encode(values)) { s, p ->
            status = s
            payload = p
            latch.countDown()
        }
        assertTrue("$method timed out", latch.await(timeoutSeconds, TimeUnit.SECONDS))
        return status to if (status == ModuleResultStatus.SUCCESS) WireMap.decode(payload) else mapOf("message" to WireValue.Text(String(payload)))
    }

    private fun Map<String, WireValue>.int(key: String) = (this[key] as WireValue.Integer).value

    private fun writeQuadrantJpeg(path: String, width: Int, height: Int, orientation: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, width / 2f, height / 2f, Paint().apply { color = Color.RED })
        }
        val file = File(root, path).apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }

    private fun copyAsset(name: String, path: String) {
        val target = File(root, path).apply { parentFile?.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }

    private fun tracks(file: File): List<MediaFormat> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return List(extractor.trackCount) { extractor.getTrackFormat(it) }
        } finally {
            extractor.release()
        }
    }

    private fun isRed(pixel: Int) = Color.red(pixel) > 180 && Color.blue(pixel) < 90
}
