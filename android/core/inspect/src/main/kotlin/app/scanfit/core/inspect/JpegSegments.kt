package app.scanfit.core.inspect

/** One marker segment of a JPEG: `[start, end)` covers the marker, the length field and the body. */
data class JpegSegment(
    val marker: Int,
    val start: Int,
    val end: Int,
) {
    /** First byte after the 2-byte length field. */
    val bodyStart: Int get() = start + HEADER_BYTES
    val isApp: Boolean get() = marker in APP0..APP15
    val isSof: Boolean get() = marker in SOF0..SOF15 && marker != DHT && marker != JPG && marker != DAC

    companion object {
        const val SOI = 0xD8
        const val EOI = 0xD9
        const val SOS = 0xDA
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val APP13 = 0xED
        const val APP14 = 0xEE
        const val APP15 = 0xEF
        const val COM = 0xFE
        const val SOF0 = 0xC0
        const val SOF2 = 0xC2
        const val SOF15 = 0xCF
        const val DHT = 0xC4
        const val JPG = 0xC8
        const val DAC = 0xCC
        const val HEADER_BYTES = 4
    }
}

/**
 * Walks the header segments of a JPEG up to and including SOS. Never throws: a truncated or corrupt file simply
 * ends the walk. `scanStart` is the offset of the first byte after the SOS segment (entropy-coded data), or -1
 * when no SOS was reached.
 */
class JpegWalk(
    val segments: List<JpegSegment>,
    val scanStart: Int,
) {
    val reachedScan: Boolean get() = scanStart >= 0

    companion object {
        private const val MIN_SEGMENT_LENGTH = 2
        private const val FF = 0xFF

        fun of(b: ByteArray): JpegWalk {
            val out = ArrayList<JpegSegment>()
            var o = 2
            while (o + 1 < b.size) {
                if (b.u8(o) != FF) {
                    o++
                    continue
                }
                val marker = b.u8(o + 1)
                when {
                    marker == FF -> {
                        o++
                    }

                    // fill byte
                    marker == JpegSegment.SOI || marker == 0x01 || marker in 0xD0..0xD7 || marker == 0x00 -> {
                        o += 2
                    }

                    marker == JpegSegment.EOI -> {
                        return JpegWalk(out, -1)
                    }

                    o + 3 >= b.size -> {
                        return JpegWalk(out, -1)
                    }

                    else -> {
                        val length = b.u16(o + 2)
                        val end = o + 2 + length
                        if (length < MIN_SEGMENT_LENGTH || end > b.size) return JpegWalk(out, -1)
                        out += JpegSegment(marker, o, end)
                        if (marker == JpegSegment.SOS) return JpegWalk(out, end)
                        o = end
                    }
                }
            }
            return JpegWalk(out, -1)
        }
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u32be(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()

internal fun ByteArray.ascii(
    from: Int,
    length: Int,
): String = if (from < 0 || from + length > size) "" else String(this, from, length, Charsets.ISO_8859_1)

internal fun ByteArray.startsWith(
    vararg values: Int,
    at: Int = 0,
): Boolean = at + values.size <= size && values.indices.all { u8(at + it) == values[it] }
