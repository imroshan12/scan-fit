package app.scanfit.core.imaging

import app.scanfit.core.inspect.Inspector
import app.scanfit.core.inspect.JpegSegment
import app.scanfit.core.inspect.JpegWalk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class JpegPatcherTest {
    private fun ok(r: PatchResult) = (r as? PatchResult.Ok)?.bytes ?: error("expected Ok, got $r")

    private fun decodes(b: ByteArray) = ImageIO.read(ByteArrayInputStream(b)) != null

    private fun markers(b: ByteArray) = JpegWalk.of(b).segments.map { it.marker }

    private fun com(n: Int) = ByteArray(n) { 0x20 }.also {
        it[0] = 0xFF.toByte()
        it[1] = 0xFE.toByte()
        it[2] =
            ((n - 2) shr 8).toByte()
        it[3] = (n - 2).toByte()
    }

    /** SOI, [extra segments], DQT-ish filler, SOF0 (comps), SOS, a few scan bytes, EOI. Enough structure for the patcher. */
    private fun jpeg(
        extra: List<ByteArray> = emptyList(),
        comps: Int = 3,
        sof: Int = 0xC0,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        extra.forEach { out.write(it) }
        val sofLen = 8 + comps * 3
        out.write(byteArrayOf(0xFF.toByte(), sof.toByte(), 0, sofLen.toByte(), 8, 0, 10, 0, 20, comps.toByte()))
        repeat(comps) { out.write(byteArrayOf((it + 1).toByte(), 0x11, 0)) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 9, 9, 9, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun app(
        marker: Int,
        body: ByteArray,
    ) = byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    private fun jfifOf(b: ByteArray) = checkNotNull(Inspector.inspect(b).jfif)

    @Test
    fun theSharedPatchCasesAllHold() {
        val cases = Cases.section("patch_cases")
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val input = Cases.image(c.getValue("input").jsonPrimitive.content)
            val dpi = c["dpi"]?.jsonPrimitive?.int ?: 200
            val gray = c["allow_grayscale"]?.jsonPrimitive?.boolean ?: false
            val result = JpegPatcher.patch(input, dpi, gray)
            val expectError = c["expect_error"]?.jsonPrimitive?.content
            if (expectError != null) {
                assertEquals("$id: error", PatchResult.Failed(PatchError.valueOf(expectError)), result)
                continue
            }
            var out = ok(result)
            c["pad_to_bytes"]?.jsonPrimitive?.int?.let { out = JpegPatcher.pad(out, it) }
            val e = c.getValue("expect") as JsonObject
            val f = Inspector.inspect(out)
            e["no_app1"]?.let {
                assertTrue(
                    "$id: APP1 gone",
                    !f.hasExif && !f.hasXmp && JpegSegment.APP1 !in markers(out),
                )
            }
            e["no_icc"]?.let { assertTrue("$id: ICC gone", !f.hasIcc) }
            e["jfif"]?.let { j ->
                val jo = j as JsonObject
                assertEquals("$id: units", jo.getValue("units").jsonPrimitive.int, jfifOf(out).units)
                assertEquals("$id: x", jo.getValue("x").jsonPrimitive.int, jfifOf(out).xDensity)
                assertEquals("$id: y", jo.getValue("y").jsonPrimitive.int, jfifOf(out).yDensity)
            }
            e["sof"]?.let { assertEquals("$id: sof", it.jsonPrimitive.content, f.sof) }
            e["components"]?.let { assertEquals("$id: components", it.jsonPrimitive.int, f.components) }
            e["size_bytes"]?.let { assertEquals("$id: size", it.jsonPrimitive.int, out.size) }
            e["decodes"]?.let { assertTrue("$id: still decodes", decodes(out)) }
        }
    }

    @Test
    fun insertsJfifWhenMissingAndRewritesItWhenPresent() {
        val bare = ok(JpegPatcher.patch(jpeg(), 300))
        assertEquals(
            JfifDensityOf(1, 300, 300),
            JfifDensityOf(jfifOf(bare).units, jfifOf(bare).xDensity, jfifOf(bare).yDensity),
        )
        val oldJfif = app(0xE0, "JFIF\u0000".toByteArray() + byteArrayOf(1, 2, 0, 0, 72, 0, 72, 0, 0))
        val rewritten = ok(JpegPatcher.patch(jpeg(listOf(oldJfif)), 200))
        assertEquals(1, markers(rewritten).count { it == 0xE0 })
        assertEquals(200, jfifOf(rewritten).xDensity)
        assertEquals(1, jfifOf(rewritten).units)
    }

    @Test
    fun dropsExifXmpIptcOtherAppsCommentsAndNonJfifApp0ButKeepsAdobe() {
        val extras =
            listOf(
                app(0xE0, "JFXX\u0000".toByteArray() + ByteArray(6)),
                app(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(8)),
                app(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()),
                app(0xE3, ByteArray(4)),
                app(0xED, "Photoshop 3.0\u0000".toByteArray()),
                app(0xEF, ByteArray(2)),
                app(0xEE, "Adobe".toByteArray() + ByteArray(7)),
                com(10),
            )
        val m = markers(ok(JpegPatcher.patch(jpeg(extras))))
        assertEquals(listOf(0xE0, 0xEE, 0xC0, 0xDA), m)
    }

    @Test
    fun srgbIccIsRemovedButAnyOtherProfileIsRefused() {
        fun icc(text: String) = app(0xE2, "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + text.toByteArray())
        assertTrue(0xE2 !in markers(ok(JpegPatcher.patch(jpeg(listOf(icc("....desc sRGB IEC61966-2.1")))))))
        assertEquals(
            PatchResult.Failed(PatchError.NON_SRGB_PROFILE),
            JpegPatcher.patch(jpeg(listOf(icc("Display P3")))),
        )
        assertEquals(
            PatchResult.Failed(PatchError.NON_SRGB_PROFILE),
            JpegPatcher.patch(jpeg(listOf(icc("Adobe RGB (1998)")))),
        )
    }

    @Test
    fun srgbDescribedInUtf16IsAlsoRecognised() {
        val utf16 = "sRGB".map { "\u0000$it" }.joinToString("")
        val icc =
            app(
                0xE2,
                "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + "desc".toByteArray() +
                    utf16.toByteArray(Charsets.ISO_8859_1),
            )
        assertTrue(0xE2 !in markers(ok(JpegPatcher.patch(jpeg(listOf(icc))))))
    }

    @Test
    fun aMultiChunkIccProfileIsJoinedBeforeTheSrgbCheck() {
        fun chunk(
            seq: Int,
            text: String,
        ) = app(
            0xE2,
            "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(seq.toByte(), 2) + text.toByteArray(),
        )
        val split = jpeg(listOf(chunk(1, "xxxxsR"), chunk(2, "GBxxxx")))
        assertTrue("sRGB split across chunks is still sRGB", 0xE2 !in markers(ok(JpegPatcher.patch(split))))
    }

    @Test
    fun structuralFailuresAreTyped() {
        assertEquals(PatchResult.Failed(PatchError.NOT_JPEG), JpegPatcher.patch(byteArrayOf(1, 2, 3)))
        assertEquals(PatchResult.Failed(PatchError.NOT_JPEG), JpegPatcher.patch(ByteArray(0)))
        assertEquals(PatchResult.Failed(PatchError.TRUNCATED), JpegPatcher.patch(jpeg().copyOf(15)))
        assertEquals(PatchResult.Failed(PatchError.NOT_BASELINE), JpegPatcher.patch(jpeg(sof = 0xC2)))
        assertEquals(PatchResult.Failed(PatchError.NOT_BASELINE), JpegPatcher.patch(jpeg(sof = 0xC1)))
        assertEquals(PatchResult.Failed(PatchError.CMYK), JpegPatcher.patch(jpeg(comps = 4)))
        assertEquals(PatchResult.Failed(PatchError.GRAYSCALE_NOT_ALLOWED), JpegPatcher.patch(jpeg(comps = 1)))
        assertEquals(PatchResult.Failed(PatchError.NOT_BASELINE), JpegPatcher.patch(jpeg(comps = 2)))
        assertNotNull(ok(JpegPatcher.patch(jpeg(comps = 1), allowGrayscale = true)))
    }

    @Test
    fun scanDataIsCopiedUntouched() {
        val src = jpeg(listOf(app(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(20))))
        val out = ok(JpegPatcher.patch(src))
        val scan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 9, 9, 9, 0xFF.toByte(), 0xD9.toByte())
        assertArrayEquals(scan, out.copyOfRange(out.size - scan.size, out.size))
    }

    @Test
    fun padReachesTheExactTargetForEveryGap() {
        val base = ok(JpegPatcher.patch(jpeg()))
        for (gap in listOf(
            4,
            5,
            6,
            7,
            8,
            100,
            65_533,
            65_537,
            65_538,
            65_539,
            65_540,
            65_541,
            65_542,
            131_074,
            200_000,
        )) {
            val padded = JpegPatcher.pad(base, base.size + gap)
            assertEquals("gap $gap", base.size + gap, padded.size)
            assertEquals("still baseline", "SOF0", Inspector.inspect(padded).sof)
        }
    }

    @Test
    fun padWithAGapBelowFourOvershootsByAtMostThree() {
        val base = ok(JpegPatcher.patch(jpeg()))
        for (gap in 1..3) assertEquals("gap $gap", base.size + 4, JpegPatcher.pad(base, base.size + gap).size)
        assertArrayEquals("no padding needed", base, JpegPatcher.pad(base, base.size))
        assertArrayEquals("smaller target is a no-op", base, JpegPatcher.pad(base, 10))
    }

    @Test
    fun paddingSegmentsSitRightAfterJfifAndContainOnlySpaces() {
        val base = ok(JpegPatcher.patch(jpeg()))
        val padded = JpegPatcher.pad(base, base.size + 300)
        assertEquals(listOf(0xE0, 0xFE, 0xC0, 0xDA), markers(padded))
        val seg = JpegWalk.of(padded).segments[1]
        assertTrue((seg.bodyStart until seg.end).all { padded[it] == 0x20.toByte() })
    }

    @Test
    fun padThenPatchAgainStripsThePaddingSoSizesAreDeterministic() {
        val base = ok(JpegPatcher.patch(jpeg()))
        assertArrayEquals(base, ok(JpegPatcher.patch(JpegPatcher.pad(base, base.size + 500))))
    }

    @Test
    fun padInsertsAfterSoiWhenThereIsNoJfif() {
        val bare = jpeg()
        val padded = JpegPatcher.pad(bare, bare.size + 20)
        assertEquals(listOf(0xFE, 0xC0, 0xDA), markers(padded))
    }

    private data class JfifDensityOf(
        val units: Int,
        val x: Int,
        val y: Int,
    )
}
