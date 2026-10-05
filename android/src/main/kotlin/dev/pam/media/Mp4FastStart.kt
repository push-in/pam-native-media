package dev.pam.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Moves the `moov` atom before `mdat` (the `+faststart` layout) so the server
 * can serve the file progressively without a remux. Chunk offsets inside
 * `stco`/`co64` are shifted by the size of the relocated `moov`. MediaMuxer
 * always writes `moov` at the end, so this pass runs after every transcode.
 */
internal object Mp4FastStart {
    private data class Atom(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private val CONTAINER_ATOMS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /** Returns true when `moov` already precedes `mdat`. */
    fun isFastStart(file: File): Boolean {
        val atoms = topLevelAtoms(file)
        val moov = atoms.firstOrNull { it.type == "moov" } ?: return false
        val mdat = atoms.firstOrNull { it.type == "mdat" } ?: return true
        return moov.offset < mdat.offset
    }

    /**
     * Writes a faststart copy of `input` into `output`. Returns false (and leaves
     * `output` untouched) when the input is already streamable or has no `moov`.
     */
    fun rewrite(input: File, output: File): Boolean {
        val atoms = topLevelAtoms(input)
        val moov = atoms.firstOrNull { it.type == "moov" } ?: return false
        val mdat = atoms.firstOrNull { it.type == "mdat" } ?: return false
        if (moov.offset < mdat.offset) return false
        if (moov.size > Int.MAX_VALUE) return false

        RandomAccessFile(input, "r").use { source ->
            val moovBytes = ByteArray(moov.size.toInt())
            source.seek(moov.offset)
            source.readFully(moovBytes)
            val moovBuffer = ByteBuffer.wrap(moovBytes)
            if (findAtom(moovBuffer, moov.headerSize, moovBytes.size, "cmov")) {
                throw IllegalStateException("Compressed moov atoms are not supported")
            }
            // Every atom that used to sit before moov now moves forward by moov.size.
            shiftChunkOffsets(moovBuffer, moov.headerSize, moovBytes.size, moov.size)

            output.outputStream().buffered(1 shl 16).use { sink ->
                val ftyp = atoms.firstOrNull { it.type == "ftyp" }
                if (ftyp != null) copyRange(source, ftyp.offset, ftyp.size, sink)
                sink.write(moovBytes)
                for (atom in atoms) {
                    // Every other atom keeps its order (and size), so mdat shifts by exactly moov.size.
                    if (atom === moov || atom === ftyp) continue
                    copyRange(source, atom.offset, atom.size, sink)
                }
            }
        }
        return true
    }

    private fun topLevelAtoms(file: File): List<Atom> {
        val atoms = mutableListOf<Atom>()
        RandomAccessFile(file, "r").use { source ->
            val length = source.length()
            var offset = 0L
            val header = ByteArray(16)
            while (offset + 8 <= length) {
                source.seek(offset)
                source.readFully(header, 0, 8)
                var size = readUInt32(header, 0)
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                var headerSize = 8
                if (size == 1L) {
                    source.readFully(header, 8, 8)
                    size = ByteBuffer.wrap(header, 8, 8).long
                    headerSize = 16
                } else if (size == 0L) {
                    size = length - offset
                }
                if (size < headerSize) break
                atoms += Atom(type, offset, size, headerSize)
                offset += size
            }
        }
        return atoms
    }

    private fun findAtom(buffer: ByteBuffer, start: Int, end: Int, wanted: String): Boolean {
        var offset = start
        while (offset + 8 <= end) {
            val size = readUInt32(buffer, offset)
            val type = atomType(buffer, offset + 4)
            var headerSize = 8
            val atomSize = when (size) {
                1L -> { headerSize = 16; buffer.getLong(offset + 8) }
                0L -> (end - offset).toLong()
                else -> size
            }
            if (atomSize < headerSize) return false
            if (type == wanted) return true
            if (type in CONTAINER_ATOMS && findAtom(buffer, offset + headerSize, (offset + atomSize).toInt(), wanted)) return true
            offset += atomSize.toInt()
        }
        return false
    }

    private fun shiftChunkOffsets(buffer: ByteBuffer, start: Int, end: Int, delta: Long) {
        var offset = start
        while (offset + 8 <= end) {
            val size = readUInt32(buffer, offset)
            val type = atomType(buffer, offset + 4)
            var headerSize = 8
            val atomSize = when (size) {
                1L -> { headerSize = 16; buffer.getLong(offset + 8) }
                0L -> (end - offset).toLong()
                else -> size
            }
            if (atomSize < headerSize) return
            val bodyStart = offset + headerSize
            when (type) {
                "stco" -> {
                    val count = readUInt32(buffer, bodyStart + 4).toInt()
                    var entry = bodyStart + 8
                    repeat(count) {
                        val shifted = readUInt32(buffer, entry) + delta
                        if (shifted > 0xFFFFFFFFL) throw IllegalStateException("stco offsets overflow 32 bits after fast start")
                        buffer.putInt(entry, shifted.toInt())
                        entry += 4
                    }
                }
                "co64" -> {
                    val count = readUInt32(buffer, bodyStart + 4).toInt()
                    var entry = bodyStart + 8
                    repeat(count) {
                        buffer.putLong(entry, buffer.getLong(entry) + delta)
                        entry += 8
                    }
                }
                in CONTAINER_ATOMS -> shiftChunkOffsets(buffer, bodyStart, (offset + atomSize).toInt(), delta)
            }
            offset += atomSize.toInt()
        }
    }

    private fun copyRange(source: RandomAccessFile, offset: Long, size: Long, sink: java.io.OutputStream) {
        source.seek(offset)
        val buffer = ByteArray(1 shl 16)
        var remaining = size
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IllegalStateException("Truncated MP4 file")
            sink.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readUInt32(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL

    private fun atomType(buffer: ByteBuffer, offset: Int): String {
        val bytes = ByteArray(4)
        for (index in 0 until 4) bytes[index] = buffer.get(offset + index)
        return String(bytes, Charsets.ISO_8859_1)
    }
}
