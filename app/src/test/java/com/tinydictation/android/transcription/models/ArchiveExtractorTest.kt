package com.tinydictation.android.transcription.models

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun normalTarExtractsUnchanged() {
        val archive = tarArchive("model/nested/a.txt")
        val target = File(tempDir, "target")

        ArchiveExtractor.extractTarBz2(archive, target)

        assertEquals("contents", File(target, "a.txt").readText())
        assertEquals("contents", File(target, "nested/a.txt").readText())
    }

    @Test
    fun normalZipExtractsUnchanged() {
        val archive = zipArchive("model/nested/a.txt")
        val target = File(tempDir, "target")

        ArchiveExtractor.extractZip(archive, target)

        assertEquals("contents", File(target, "a.txt").readText())
        assertEquals("contents", File(target, "nested/a.txt").readText())
    }

    @Test
    fun tarEntryOutsideTargetIsRejected() {
        val archive = tarArchive("model/../escape.txt")
        val target = File(tempDir, "target")

        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extractTarBz2(archive, target)
        }

        assertEquals("contents", File(target, "a.txt").readText())
        assertFalse(File(tempDir, "escape.txt").exists())
    }

    @Test
    fun zipEntryOutsideTargetIsRejected() {
        val archive = zipArchive("model/../escape.txt")
        val target = File(tempDir, "target")

        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extractZip(archive, target)
        }

        assertEquals("contents", File(target, "a.txt").readText())
        assertFalse(File(tempDir, "escape.txt").exists())
    }

    @Test
    fun tarLinksAreRejected() {
        val archive = File(tempDir, "links.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { compressed ->
            compressed.writeTarEntry("model/link", type = '2', linkName = "../escape.txt")
            compressed.write(ByteArray(1024))
        }
        val target = File(tempDir, "target")

        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extractTarBz2(archive, target)
        }

        assertFalse(File(target, "link").exists())
        assertFalse(File(tempDir, "escape.txt").exists())
    }

    private fun tarArchive(member: String): File {
        val archive = File(tempDir, "model.tar.bz2")
        val contents = "contents".toByteArray()
        BZip2CompressorOutputStream(archive.outputStream()).use { compressed ->
            compressed.writeTarEntry("model/", type = '5')
            for (name in listOf("model/a.txt", member)) {
                compressed.writeTarEntry(name, contents = contents)
            }
            compressed.write(ByteArray(1024))
        }
        return archive
    }

    private fun OutputStream.writeTarEntry(
        name: String,
        type: Char = '0',
        contents: ByteArray = byteArrayOf(),
        linkName: String = ""
    ) {
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        val mode = if (type == '5') "0000755\u0000" else "0000644\u0000"
        mode.toByteArray(Charsets.US_ASCII).copyInto(header, 100)
        "0000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 108)
        "0000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 116)
        val size = contents.size.toString(8).padStart(11, '0') + "\u0000"
        size.toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        "00000000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 136)
        header.fill(32.toByte(), 148, 156)
        header[156] = type.code.toByte()
        linkName.toByteArray(Charsets.US_ASCII).copyInto(header, 157)
        "ustar\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        "00".toByteArray(Charsets.US_ASCII).copyInto(header, 263)
        val checksum = header.sumOf { it.toInt() and 0xff }
        val checksumField = checksum.toString(8).padStart(6, '0') + "\u0000 "
        checksumField.toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        write(header)
        write(contents)
        write(ByteArray((512 - contents.size % 512) % 512))
    }

    private fun zipArchive(member: String): File {
        val archive = File(tempDir, "model.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("model/"))
            zip.closeEntry()
            for (name in listOf("model/a.txt", member)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("contents".toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }
}
