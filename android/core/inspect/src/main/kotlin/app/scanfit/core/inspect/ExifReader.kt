package app.scanfit.core.inspect

/** Reads just the orientation tag and whether a GPS IFD exists. Tolerates truncated or hostile EXIF. */
internal object ExifReader {
    data class Exif(
        val orientation: Int?,
        val hasGps: Boolean,
    )

    private const val TIFF_AT = 6 // after "Exif\0\0"
    private const val IFD_ENTRY_BYTES = 12
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_GPS_IFD = 0x8825
    private const val VALUE_AT = 8
    private const val BYTES_32 = 4
    private const val MAX_ENTRIES = 512

    fun read(
        b: ByteArray,
        bodyStart: Int,
        end: Int,
    ): Exif {
        val tiff = bodyStart + TIFF_AT
        if (tiff + 8 > end) return Exif(null, false)
        val little = b.ascii(tiff, 2) == "II"
        if (!little && b.ascii(tiff, 2) != "MM") return Exif(null, false)

        fun r16(o: Int) = if (little) b.u8(o) or (b.u8(o + 1) shl 8) else b.u16(o)

        fun r32(o: Int): Long = if (little) r16(o).toLong() or (r16(o + 2).toLong() shl 16) else b.u32be(o)

        val ifd0 = tiff + r32(tiff + BYTES_32).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (ifd0 < tiff || ifd0 + 2 > end) return Exif(null, false)
        val count = minOf(r16(ifd0), MAX_ENTRIES)
        var orientation: Int? = null
        var gps = false
        for (i in 0 until count) {
            val entry = ifd0 + 2 + i * IFD_ENTRY_BYTES
            if (entry + IFD_ENTRY_BYTES > end) break
            when (r16(entry)) {
                TAG_ORIENTATION -> orientation = r16(entry + VALUE_AT).takeIf { it in 1..8 }
                TAG_GPS_IFD -> gps = true
            }
        }
        return Exif(orientation, gps)
    }
}
