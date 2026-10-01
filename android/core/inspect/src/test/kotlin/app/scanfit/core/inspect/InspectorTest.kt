package app.scanfit.core.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** SOI + optional APP1(EXIF) + SOF0 + EOI, hand-built so every field is under test control. */
    private fun jpeg(
        components: Int = 3,
        w: Int = 20,
        h: Int = 10,
        sofMarker: Int = 0xC0,
        exif: ByteArray? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        fun add(vararg v: Int) = v.forEach { out += it.toByte() }
        add(0xFF, 0xD8)
        if (exif != null) {
            add(0xFF, 0xE1, (exif.size + 2) shr 8, (exif.size + 2) and 0xFF)
            exif.forEach { out += it }
        }
        val len = 8 + components * 3
        add(0xFF, sofMarker, len shr 8, len and 0xFF, 8, h shr 8, h and 0xFF, w shr 8, w and 0xFF, components)
        repeat(components) { add(it + 1, 0x11, 0) }
        add(0xFF, 0xD9)
        return out.toByteArray()
    }

    private fun tiff(little: Boolean, entries: List<Pair<Int, Int>>): ByteArray {
        val out = ArrayList<Byte>()

        fun w16(v: Int) {
            val lo = v.toByte()
            val hi = (v shr 8).toByte()
            if (little) {
                out += lo
                out += hi
            } else {
                out += hi
                out += lo
            }
        }

        fun w32(v: Int) {
            if (little) {
                w16(v and 0xFFFF)
                w16(v ushr 16)
            } else {
                w16(v ushr 16)
                w16(v and 0xFFFF)
            }
        }
        ascii("Exif\u0000\u0000").forEach { out += it }
        ascii(if (little) "II" else "MM").forEach { out += it }
        w16(42)
        w32(8)
        w16(entries.size)
        for ((tag, value) in entries) {
            w16(tag)
            w16(3)
            w32(1)
            w16(value)
            w16(0)
        }
        w32(0)
        return out.toByteArray()
    }

    @Test
    fun formatComesFromMagicBytesNotTheExtension() {
        assertEquals(DetectedFormat.PNG, Inspector.detectFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals(DetectedFormat.PDF, Inspector.detectFormat(ascii("%PDF-1.7\n")))
        assertEquals(DetectedFormat.JPEG, Inspector.detectFormat(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(DetectedFormat.UNKNOWN, Inspector.detectFormat(bytes(1, 2, 3)))
        assertEquals(DetectedFormat.UNKNOWN, Inspector.detectFormat(ByteArray(0)))
        val heic = ByteArray(16).also { ascii("ftypheic").copyInto(it, 4) }
        assertEquals(DetectedFormat.HEIC, Inspector.detectFormat(heic))
        val webp = ByteArray(16).also {
            ascii("RIFF").copyInto(it, 0)
            ascii("WEBP").copyInto(it, 8)
        }
        assertEquals(DetectedFormat.WEBP, Inspector.detectFormat(webp))
    }

    @Test
    fun jpegComponentsMapToColour() {
        assertEquals(ColorKind.RGB, Inspector.inspect(jpeg(3)).color)
        assertEquals(ColorKind.GRAY, Inspector.inspect(jpeg(1)).color)
        val cmyk = Inspector.inspect(jpeg(4), "x.jpg")
        assertEquals(ColorKind.CMYK, cmyk.color)
        assertEquals(listOf(Issue.CMYK_COLOR), cmyk.issues)
    }

    @Test
    fun progressiveSofsAreDetected() {
        for (sof in listOf(0xC2, 0xC6, 0xCA, 0xCE)) assertTrue("SOF${sof - 0xC0}", Inspector.inspect(jpeg(sofMarker = sof)).progressive)
        for (sof in listOf(0xC0, 0xC1)) assertFalse("SOF${sof - 0xC0}", Inspector.inspect(jpeg(sofMarker = sof)).progressive)
        assertEquals("SOF2", Inspector.inspect(jpeg(sofMarker = 0xC2)).sof)
    }

    @Test
    fun dhtJpgAndDacMarkersAreNotFrameHeaders() {
        // 0xC4 (DHT), 0xC8 (JPG) and 0xCC (DAC) sit inside the SOF range but are not frames.
        for (m in listOf(0xC4, 0xC8, 0xCC)) assertNull("marker $m", Inspector.inspect(jpeg(sofMarker = m)).width)
    }

    @Test
    fun exifOrientationAndGpsAreReadInBothByteOrders() {
        for (little in listOf(true, false)) {
            val f = Inspector.inspect(jpeg(exif = tiff(little, listOf(0x0112 to 6, 0x8825 to 0))), "p.jpg")
            assertEquals("little=$little", 6, f.exifOrientation)
            assertTrue(f.hasGps)
            assertTrue(f.hasExif)
            assertEquals(listOf(Issue.HAS_GPS_EXIF, Issue.ROTATED_BY_EXIF), f.issues)
        }
        assertNull("orientation outside 1..8 is ignored", Inspector.inspect(jpeg(exif = tiff(true, listOf(0x0112 to 9)))).exifOrientation)
        assertTrue("orientation 1 is not a rotation", Inspector.inspect(jpeg(exif = tiff(true, listOf(0x0112 to 1)))).issues.isEmpty())
    }

    @Test
    fun hostileOrTruncatedInputNeverThrows() {
        val inputs = listOf(
            ByteArray(0),
            bytes(0xFF),
            bytes(0xFF, 0xD8),
            bytes(0xFF, 0xD8, 0xFF),
            bytes(0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xFF),
            bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x02), bytes(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x03, 0x08),
            ascii("%PDF-"), bytes(0x89, 0x50, 0x4E, 0x47),
            // EXIF claiming a huge IFD offset and a huge entry count
            jpeg(exif = ascii("Exif\u0000\u0000II").plus(bytes(42, 0, 0xFF, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF))),
        )
        for (input in inputs) Inspector.inspect(input, "broken.jpg")
        // a segment whose declared length runs past the end of the file
        assertNull(Inspector.inspect(bytes(0xFF, 0xD8, 0xFF, 0xC0, 0x7F, 0xFF, 8, 0, 10, 0, 20, 3)).width)
    }

    @Test
    fun pngReadsIhdr() {
        val png = ByteArray(33)
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(png)
        bytes(0, 0, 1, 0xA4).copyInto(png, 16) // width 420
        bytes(0, 0, 0, 0xB4).copyInto(png, 20) // height 180
        png[24] = 8
        png[25] = 2
        val f = Inspector.inspect(png, "s.png")
        assertEquals(420, f.width)
        assertEquals(180, f.height)
        assertEquals(ColorKind.RGB, f.color)
        assertEquals(ColorKind.GRAY, Inspector.inspect(png.also { it[25] = 0 }).color)
        assertEquals("a PNG header that is too short still reports PNG", DetectedFormat.PNG, Inspector.inspect(bytes(0x89, 0x50, 0x4E, 0x47)).format)
    }

    @Test
    fun pdfPagesEncryptionAndImageOnly() {
        val pdf = "%PDF-1.4\n1 0 obj<</Type/Page>>endobj\n2 0 obj<</Type/Pages>>endobj\n3 0 obj<</Type /Page>>endobj\ntrailer<</Encrypt 9 0 R>>"
        val f = Inspector.inspect(ascii(pdf), "a.pdf")
        assertEquals(2, f.pdfPages)
        assertTrue(f.pdfEncrypted)
        assertTrue(f.pdfImageOnly)
        assertEquals(listOf(Issue.PDF_ENCRYPTED), f.issues)
        assertFalse(Inspector.inspect(ascii("%PDF-1.4 /Font <<>>")).pdfImageOnly)
        assertFalse(Inspector.inspect(ascii("%PDF-1.4 /Type/Page /Encryption")).pdfEncrypted)
    }

    @Test
    fun extensionMismatchOnlyWhenTheExtensionNamesADifferentKnownFormat() {
        val png = ByteArray(33).also { bytes(0x89, 0x50, 0x4E, 0x47).copyInto(it) }
        assertEquals(listOf(Issue.EXTENSION_MISMATCH), Inspector.inspect(png, "photo.JPG").issues)
        assertTrue(Inspector.inspect(png, "photo.png").issues.isEmpty())
        assertTrue("unknown extension", Inspector.inspect(png, "photo.dat").issues.isEmpty())
        assertTrue("no extension", Inspector.inspect(png, "photo").issues.isEmpty())
        val heic = ByteArray(16).also { ascii("ftypheic").copyInto(it, 4) }
        assertEquals(listOf(Issue.HEIC_NOT_ACCEPTED), Inspector.inspect(heic, "a.heif").issues)
        assertEquals(listOf(Issue.EXTENSION_MISMATCH, Issue.HEIC_NOT_ACCEPTED), Inspector.inspect(heic, "a.jpg").issues)
    }

    @Test
    fun kbIs1024Bytes() {
        assertEquals(2.0, Inspector.inspect(ByteArray(2048)).kb, 0.0)
        assertNotNull(Inspector.inspect(ByteArray(3)))
    }
}
