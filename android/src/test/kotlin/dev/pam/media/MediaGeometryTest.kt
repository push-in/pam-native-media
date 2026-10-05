package dev.pam.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGeometryTest {
    @Test
    fun containFitsInsideTheBox() {
        val plan = ImagePipeline.resizePlan(4000.0, 3000.0, ImageOperation.Resize(1000, 1000, 1), onlyScaleDown = true)
        assertEquals(ImagePipeline.ResizePlan(1000, 750, 1000, 750), plan)
        assertEquals(ImagePipeline.ResizePlan(500, 375, 500, 375), ImagePipeline.resizePlan(4000.0, 3000.0, ImageOperation.Resize(500, 0, 1), true))
    }

    @Test
    fun onlyScaleDownNeverEnlarges() {
        assertEquals(ImagePipeline.ResizePlan(300, 200, 300, 200), ImagePipeline.resizePlan(300.0, 200.0, ImageOperation.Resize(1200, 1200, 1), true))
        assertEquals(ImagePipeline.ResizePlan(1200, 800, 1200, 800), ImagePipeline.resizePlan(300.0, 200.0, ImageOperation.Resize(1200, 1200, 1), false))
        assertEquals(ImagePipeline.ResizePlan(300, 200, 300, 200), ImagePipeline.resizePlan(300.0, 200.0, ImageOperation.Resize(1200, 1200, 3), true))
    }

    @Test
    fun coverFillsThenCentersTheCrop() {
        assertEquals(ImagePipeline.ResizePlan(400, 300, 300, 300), ImagePipeline.resizePlan(4000.0, 3000.0, ImageOperation.Resize(300, 300, 2), true))
        assertEquals(ImagePipeline.ResizePlan(400, 200, 200, 200), ImagePipeline.resizePlan(400.0, 200.0, ImageOperation.Resize(500, 500, 2), true))
    }

    @Test
    fun decodeScaleFollowsTheFirstResize() {
        assertEquals(0.25, ImagePipeline.requiredScale(4000, 3000, listOf(ImageOperation.Resize(1000, 1000, 1)), true), 1e-9)
        assertEquals(1.0, ImagePipeline.requiredScale(4000, 3000, listOf(ImageOperation.Rotate(90)), true), 1e-9)
        assertEquals(0.5, ImagePipeline.requiredScale(4000, 3000, listOf(ImageOperation.Crop(0, 0, 2000, 2000), ImageOperation.Resize(1000, 1000, 1)), true), 1e-9)
    }

    @Test
    fun transcodeOptionsAreBounded() {
        assertEquals(TranscodeOptions(2, 1_200_000, true, true), TranscodeOptions.parse("""{"preset":2,"maxBitrate":1200000,"fastStart":true}"""))
        assertTrue(runCatching { TranscodeOptions.parse("""{"preset":9}""") }.isFailure)
    }

    @Test
    fun fastStartMovesMoovAndShiftsChunkOffsets() {
        val ftyp = atom("ftyp", "isom0000".toByteArray())
        val mdatPayload = ByteArray(32) { it.toByte() }
        val mdat = atom("mdat", mdatPayload)
        val mdatDataOffset = ftyp.size + 8
        val stco = atom("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(mdatDataOffset).array())
        val moov = atom("moov", atom("trak", atom("mdia", atom("minf", atom("stbl", stco)))))
        val input = File.createTempFile("slow", ".mp4").apply { writeBytes(ftyp + mdat + moov) }
        val output = File.createTempFile("fast", ".mp4")
        assertFalse(Mp4FastStart.isFastStart(input))
        assertTrue(Mp4FastStart.rewrite(input, output))
        assertTrue(Mp4FastStart.isFastStart(output))
        val bytes = output.readBytes()
        val offset = ByteBuffer.wrap(bytes, bytes.size - mdat.size - 4, 4).int
        assertEquals("stco must point at the relocated mdat payload", ftyp.size + moov.size + 8, offset)
        assertArrayEquals(mdatPayload, bytes.copyOfRange(ftyp.size + moov.size + 8, ftyp.size + moov.size + 8 + mdatPayload.size))
        assertFalse("already fast-start inputs are left alone", Mp4FastStart.rewrite(output, File.createTempFile("again", ".mp4")))
    }

    private fun atom(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).apply {
            writeInt(payload.size + 8)
            writeBytes(type)
            write(payload)
        }
    }.toByteArray()
}
