package dev.pam.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapabilitiesTest {
    @Test
    fun mediaAlbumAndFileNamesAreSanitized() {
        assertEquals("real-world app", MediaStoreSaver.normalizedAlbum(" real-world app "))
        assertEquals(null, MediaStoreSaver.normalizedAlbum(""))
        assertThrows(IllegalArgumentException::class.java) { MediaStoreSaver.normalizedAlbum("../x") }
        assertEquals("a_b.jpg", MediaStoreSaver.normalizedDisplayName("a/b.jpg"))
    }
}
