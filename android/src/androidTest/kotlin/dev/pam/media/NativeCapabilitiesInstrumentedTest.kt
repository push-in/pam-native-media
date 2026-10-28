package dev.pam.media

import dev.pam.nativeapp.modules.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.CapabilityTestActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCapabilitiesInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test
    fun mediaStoreSaverPublishesImagesToTheSharedLibrary() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val source = File(context.cacheDir, "pam-save-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, source.outputStream())
        val saved = MediaStoreSaver(context).save(source, "image/png", "PAM Tests", source.name)
        val uri = Uri.parse(saved.getString("uri"))
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null).use { cursor ->
                assertNotNull(cursor)
                assertTrue(cursor!!.moveToFirst())
                assertEquals("Pictures/PAM Tests/", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            assertEquals("PAM Tests", saved.getString("albumTitle"))
        } finally {
            context.contentResolver.delete(uri, null, null)
            source.delete()
        }
    }

    private fun call(module: NativeModule, method: String, values: Map<String, WireValue>): Map<String, WireValue> {
        val latch = CountDownLatch(1)
        val status = AtomicReference<ModuleResultStatus>()
        val payload = AtomicReference<ByteArray>()
        module.invoke(method, WireMap.encode(values)) { resultStatus, bytes ->
            status.set(resultStatus)
            payload.set(bytes)
            latch.countDown()
        }
        assertTrue("$method timed out", latch.await(20, TimeUnit.SECONDS))
        check(status.get() == ModuleResultStatus.SUCCESS) { "$method failed: ${String(payload.get())}" }
        return if (payload.get().isEmpty()) emptyMap() else WireMap.decode(payload.get())
    }

    private class Request(val head: String, val body: ByteArray)

    /** One-shot HTTP/1.1 server that records the request and replies with a fixed response. */
    private class LocalServer(response: String, body: ByteArray = ByteArray(0)) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 20_000 }
        private val received = AtomicReference<Request>()
        private val failure = AtomicReference<Throwable>()
        private val done = CountDownLatch(1)
        val port: Int = socket.localPort
        private val worker = thread(name = "pam-capability-test-server") {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 20_000
                    val input = client.getInputStream()
                    val head = StringBuilder()
                    var length = 0
                    while (true) {
                        val line = line(input)
                        if (line.isEmpty()) break
                        head.append(line).append("\r\n")
                        if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                    }
                    val requestBody = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(requestBody, read, length - read)
                        require(count > 0) { "Request ended early" }
                        read += count
                    }
                    received.set(Request(head.toString(), requestBody))
                    client.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }

        fun request(): Request {
            assertTrue("server did not finish", done.await(20, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            return received.get()
        }

        fun requestOrNull(timeoutMillis: Long): Request? {
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
            return received.get()
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
        }

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                require(value >= 0) { "Connection closed" }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
