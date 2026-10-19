package app.scanfit.core.imaging

/**
 * Photographed pages for the PDF engine (ALGORITHMS 6.2 / 9.10): the paper turns white and ink, photos and seals stay
 * dark, by the thumb's paper-only tone (9.5 steps 1–4). Grey output, or each colour channel scaled by the same gain.
 */
object PageCleanup {
    private const val BYTE = 255

    fun clean(
        src: Raster,
        grayscale: Boolean,
    ): Raster {
        val (l, tone) = InkCleanup.paperTone(src)
        val out = ByteArray(src.rgb.size)
        for (p in l.indices) {
            val i = p * Raster.CHANNELS
            if (grayscale) {
                val v = tone[p].toByte()
                out[i] = v
                out[i + 1] = v
                out[i + 2] = v
            } else {
                val luma = l[p]
                val g = tone[p]
                for (c in 0 until Raster.CHANNELS) {
                    val channel = src.rgb[i + c].toInt() and BYTE
                    out[i + c] = minOf(BYTE, (channel * g + luma / 2) / maxOf(luma, 1)).toByte()
                }
            }
        }
        return Raster(src.width, src.height, out)
    }

    /** Every pixel becomes its luma, R = G = B = L (ALGORITHMS 6.2 grayscale without cleanup). */
    fun grayscale(src: Raster): Raster {
        val l = src.luma()
        val out = ByteArray(src.rgb.size)
        for (p in l.indices) {
            val i = p * Raster.CHANNELS
            out[i] = l[p]
            out[i + 1] = l[p]
            out[i + 2] = l[p]
        }
        return Raster(src.width, src.height, out)
    }
}
