package app.scanfit.core.imaging

/**
 * Applies an EXIF orientation (1-8) so the pixels are upright; the output never carries an orientation tag
 * (ALGORITHMS 1.1). `dest(x, y)` is read from `src` per the standard EXIF transforms.
 */
object Orientation {
    fun apply(
        src: Raster,
        orientation: Int,
    ): Raster {
        val sw = src.width
        val sh = src.height
        val swapped = orientation >= TRANSPOSE
        if (orientation !in 2..8) return src
        val dw = if (swapped) sh else sw
        val dh = if (swapped) sw else sh
        val out = ByteArray(dw * dh * Raster.CHANNELS)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                val sx: Int
                val sy: Int
                when (orientation) {
                    MIRROR_H -> {
                        sx = sw - 1 - x
                        sy = y
                    }

                    // mirror horizontally
                    ROTATE_180 -> {
                        sx = sw - 1 - x
                        sy = sh - 1 - y
                    }

                    // rotate 180
                    MIRROR_V -> {
                        sx = x
                        sy = sh - 1 - y
                    }

                    // mirror vertically
                    TRANSPOSE -> {
                        sx = y
                        sy = x
                    }

                    // transpose
                    ROTATE_CW -> {
                        sx = y
                        sy = sh - 1 - x
                    }

                    // rotate 90 clockwise
                    TRANSVERSE -> {
                        sx = sw - 1 - y
                        sy = sh - 1 - x
                    }

                    // transverse
                    else -> {
                        sx = sw - 1 - y
                        sy = x
                    } // 8: rotate 90 counter-clockwise
                }
                System.arraycopy(
                    src.rgb,
                    (sy * sw + sx) * Raster.CHANNELS,
                    out,
                    (y * dw + x) * Raster.CHANNELS,
                    Raster.CHANNELS,
                )
            }
        }
        return Raster(dw, dh, out)
    }

    private const val MIRROR_H = 2
    private const val ROTATE_180 = 3
    private const val MIRROR_V = 4
    private const val TRANSPOSE = 5
    private const val ROTATE_CW = 6
    private const val TRANSVERSE = 7
}
