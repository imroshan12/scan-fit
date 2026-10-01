package app.scanfit.core.imaging

/** Replaces the background with white using a person mask (ALGORITHMS 2.3 / 9.6). Person pixels are never altered. */
object BackgroundWhitening {
    private const val FEATHER_PASSES = 2
    private const val BOX_AREA = 9
    private const val OPAQUE = 255

    /** @param mask alpha per pixel (0 background, 255 person), `src.width * src.height` long. */
    fun composite(src: Raster, mask: ByteArray): Raster {
        require(mask.size == src.width * src.height) { "mask size ${mask.size} != ${src.width * src.height}" }
        var alpha = IntArray(mask.size) { mask[it].toInt() and OPAQUE }
        repeat(FEATHER_PASSES) { alpha = box3x3(alpha, src.width, src.height) }
        val out = ByteArray(src.rgb.size)
        for (p in alpha.indices) {
            val a = alpha[p]
            for (c in 0 until Raster.CHANNELS) {
                val v = src.rgb[p * Raster.CHANNELS + c].toInt() and OPAQUE
                out[p * Raster.CHANNELS + c] =
                    if (a == OPAQUE) v.toByte() else ((v * a + OPAQUE * (OPAQUE - a) + OPAQUE / 2) / OPAQUE).toByte()
            }
        }
        return Raster(src.width, src.height, out)
    }

    private fun box3x3(a: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += a[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                    }
                }
                out[y * w + x] = (sum + BOX_AREA / 2) / BOX_AREA
            }
        }
        return out
    }
}
