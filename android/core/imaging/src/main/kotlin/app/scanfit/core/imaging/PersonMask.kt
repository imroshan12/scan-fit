package app.scanfit.core.imaging

/**
 * Turns a segmentation model's person confidence (0..1 per pixel, row-major, `maskW×maskH`) into the whitening
 * mask for a `width×height` raster (ALGORITHMS 9.6): resampled bilinearly when the sizes differ, then a hard
 * threshold at 0.5 into 0 (background) or 255 (person). The feathering in [BackgroundWhitening] is the only softening.
 */
object PersonMask {
    private const val THRESHOLD = 0.5f
    private const val PERSON: Byte = -1 // 255
    private const val HALF = 0.5

    fun fromConfidence(
        confidence: FloatArray,
        maskW: Int,
        maskH: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        require(maskW > 0 && maskH > 0 && confidence.size == maskW * maskH) {
            "mask ${confidence.size} != ${maskW}x$maskH"
        }
        require(width > 0 && height > 0)
        val out = ByteArray(width * height)
        val sx = maskW.toDouble() / width
        val sy = maskH.toDouble() / height
        for (y in 0 until height) {
            val fy = ((y + HALF) * sy - HALF).coerceIn(0.0, (maskH - 1).toDouble())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, maskH - 1)
            val ty = (fy - y0).toFloat()
            for (x in 0 until width) {
                val fx = ((x + HALF) * sx - HALF).coerceIn(0.0, (maskW - 1).toDouble())
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, maskW - 1)
                val tx = (fx - x0).toFloat()
                val top = confidence[y0 * maskW + x0] * (1 - tx) + confidence[y0 * maskW + x1] * tx
                val bottom = confidence[y1 * maskW + x0] * (1 - tx) + confidence[y1 * maskW + x1] * tx
                if (top * (1 - ty) + bottom * ty >= THRESHOLD) out[y * width + x] = PERSON
            }
        }
        return out
    }
}
