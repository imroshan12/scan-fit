package app.scanfit.core.imaging

import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.inspect.JpegSegment
import app.scanfit.core.inspect.JpegWalk

enum class PatchError { NOT_JPEG, TRUNCATED, NOT_BASELINE, CMYK, GRAYSCALE_NOT_ALLOWED, NON_SRGB_PROFILE }

sealed interface PatchResult {
    class Ok(
        val bytes: ByteArray,
    ) : PatchResult

    data class Failed(
        val error: PatchError,
    ) : PatchResult
}

/**
 * Byte post-processing for every exported JPEG (ALGORITHMS 1.5 / 9.3): strip metadata, force a JFIF header with the
 * slot's DPI, assert baseline + RGB, and pad to a minimum size with COM segments. Pure bytes: no Android imports.
 */
object JpegPatcher {
    internal const val DEFAULT_DPI = 200
    private const val SOF0 = 0xC0
    private const val ICC_HEADER = "ICC_PROFILE\u0000"
    private const val ICC_CHUNK_HEADER = 14 // "ICC_PROFILE\0" + sequence number + count
    private const val MIN_COM = 4
    private const val JFIF_ID_LENGTH = 5
    private const val MAX_COM = 65_537 // 2 marker + 2 length + 65 533 payload
    private const val MAX_SHORT_GAP = 3
    private const val PAD_BYTE: Byte = 0x20

    fun patch(
        bytes: ByteArray,
        dpi: Int = DEFAULT_DPI,
        allowGrayscale: Boolean = false,
    ): PatchResult {
        if (Inspector.detectFormat(bytes) != DetectedFormat.JPEG) return failed(PatchError.NOT_JPEG)
        val walk = JpegWalk.of(bytes)
        if (!walk.reachedScan) return failed(PatchError.TRUNCATED)
        val info = Inspector.inspect(bytes)
        when {
            info.sof == null -> return failed(PatchError.TRUNCATED)
            info.sof != "SOF0" -> return failed(PatchError.NOT_BASELINE)
            info.components == CMYK_COMPONENTS -> return failed(PatchError.CMYK)
            info.components == 1 && !allowGrayscale -> return failed(PatchError.GRAYSCALE_NOT_ALLOWED)
            info.components != 1 && info.components != RGB_COMPONENTS -> return failed(PatchError.NOT_BASELINE)
        }
        val icc = iccProfile(bytes, walk)
        if (icc != null && !isSrgb(icc)) return failed(PatchError.NON_SRGB_PROFILE)

        val out = java.io.ByteArrayOutputStream(bytes.size)
        out.write(byteArrayOf(0xFF.toByte(), JpegSegment.SOI.toByte()))
        out.write(jfif(dpi))
        for (seg in walk.segments) {
            if (keep(seg)) out.write(bytes, seg.start, seg.end - seg.start)
        }
        out.write(bytes, walk.scanStart, bytes.size - walk.scanStart)
        return PatchResult.Ok(out.toByteArray())
    }

    /**
     * Inserts COM segments right after the JFIF header until the file is exactly [targetBytes]
     * (overshoot of at most 3 bytes only when the gap starts below 4).
     */
    fun pad(
        bytes: ByteArray,
        targetBytes: Int,
    ): ByteArray {
        var gap = targetBytes - bytes.size
        if (gap <= 0) return bytes
        val at = insertionPoint(bytes)
        val out = java.io.ByteArrayOutputStream(targetBytes + MIN_COM)
        out.write(bytes, 0, at)
        while (gap > 0) {
            var n = minOf(gap, MAX_COM)
            if (gap < MIN_COM) n = MIN_COM
            val remainder = gap - n
            if (remainder in 1..MAX_SHORT_GAP) n -= MIN_COM - remainder
            out.write(com(n))
            gap -= n
        }
        out.write(bytes, at, bytes.size - at)
        return out.toByteArray()
    }

    private fun keep(seg: JpegSegment): Boolean = when {
        seg.marker == JpegSegment.APP14 -> true

        seg.marker == JpegSegment.APP2 -> false

        // only reached for an sRGB profile (others failed above)
        seg.isApp -> false

        // APP0 (rewritten), APP1 EXIF/XMP, APP13 IPTC and every other APPn
        seg.marker == JpegSegment.COM -> false

        else -> true
    }

    private fun jfif(dpi: Int): ByteArray = byteArrayOf(
        0xFF.toByte(),
        JpegSegment.APP0.toByte(),
        0x00,
        0x10,
        'J'.code.toByte(),
        'F'.code.toByte(),
        'I'.code.toByte(),
        'F'.code.toByte(),
        0x00,
        0x01,
        0x01, // version 1.01
        0x01, // units: dots per inch
        (dpi shr 8).toByte(),
        dpi.toByte(),
        (dpi shr 8).toByte(),
        dpi.toByte(),
        0x00,
        0x00, // no thumbnail
    )

    private fun com(totalLength: Int): ByteArray {
        val out = ByteArray(totalLength) { PAD_BYTE }
        out[0] = 0xFF.toByte()
        out[1] = JpegSegment.COM.toByte()
        val length = totalLength - 2
        out[2] = (length shr 8).toByte()
        out[3] = length.toByte()
        return out
    }

    private fun insertionPoint(bytes: ByteArray): Int {
        val walk = JpegWalk.of(bytes)
        val jfif = walk.segments.firstOrNull { it.marker == JpegSegment.APP0 && isJfif(bytes, it) }
        return jfif?.end ?: 2
    }

    private fun isJfif(
        b: ByteArray,
        seg: JpegSegment,
    ) = seg.bodyStart + JFIF_ID_LENGTH <= b.size &&
        String(b, seg.bodyStart, JFIF_ID_LENGTH, Charsets.ISO_8859_1) == "JFIF\u0000"

    /** Concatenated ICC data from all APP2 chunks, or null when there is no ICC profile. */
    private fun iccProfile(
        b: ByteArray,
        walk: JpegWalk,
    ): String? {
        val chunks =
            walk.segments.filter {
                it.marker == JpegSegment.APP2 && it.bodyStart + ICC_HEADER.length <= b.size &&
                    String(b, it.bodyStart, ICC_HEADER.length - 1, Charsets.ISO_8859_1) == ICC_HEADER.dropLast(1)
            }
        if (chunks.isEmpty()) return null
        return chunks.joinToString("") {
            String(
                b,
                it.bodyStart + ICC_CHUNK_HEADER,
                maxOf(
                    0,
                    it.end - it.bodyStart - ICC_CHUNK_HEADER,
                ),
                Charsets.ISO_8859_1,
            )
        }
    }

    /** ICC v2 stores descriptions as ASCII, v4 (`mluc`) as UTF-16BE: accept `sRGB` in either form. */
    private fun isSrgb(profile: String) = profile.contains("sRGB") || profile.contains("\u0000s\u0000R\u0000G\u0000B")

    private fun failed(e: PatchError) = PatchResult.Failed(e)

    private const val CMYK_COMPONENTS = 4
    private const val RGB_COMPONENTS = 3
}
