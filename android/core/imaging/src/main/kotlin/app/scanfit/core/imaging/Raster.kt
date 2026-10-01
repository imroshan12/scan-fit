package app.scanfit.core.imaging

/** 8-bit sRGB, interleaved R,G,B, row-major, no alpha (ALGORITHMS 9.1). */
class Raster(
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "empty raster ${width}x$height" }
        require(rgb.size == width * height * CHANNELS) { "buffer size ${rgb.size} != ${width * height * CHANNELS}" }
    }

    val longSide: Int get() = maxOf(width, height)

    fun r(
        x: Int,
        y: Int,
    ): Int = rgb[(y * width + x) * CHANNELS].toInt() and BYTE

    fun g(
        x: Int,
        y: Int,
    ): Int = rgb[(y * width + x) * CHANNELS + 1].toInt() and BYTE

    fun b(
        x: Int,
        y: Int,
    ): Int = rgb[(y * width + x) * CHANNELS + 2].toInt() and BYTE

    /** Integer luma plane, `L = (299R + 587G + 114B + 500) / 1000` (ALGORITHMS 9.1). */
    fun luma(): ByteArray {
        val out = ByteArray(width * height)
        var i = 0
        for (p in 0 until width * height) {
            val r = rgb[i].toInt() and BYTE
            val g = rgb[i + 1].toInt() and BYTE
            val b = rgb[i + 2].toInt() and BYTE
            out[p] = ((LUMA_R * r + LUMA_G * g + LUMA_B * b + LUMA_ROUND) / LUMA_DIV).toByte()
            i += CHANNELS
        }
        return out
    }

    /** Copies the window; anything outside this raster is white. The window may start at negative coordinates. */
    fun crop(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Raster {
        val out = ByteArray(w * h * CHANNELS) { WHITE }
        for (dy in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= height) continue
            val from = maxOf(0, -x)
            val to = minOf(w, width - x)
            if (from >= to) continue
            System.arraycopy(
                rgb,
                (sy * width + x + from) * CHANNELS,
                out,
                (dy * w + from) * CHANNELS,
                (to - from) * CHANNELS,
            )
        }
        return Raster(w, h, out)
    }

    companion object {
        const val CHANNELS = 3
        private const val BYTE = 0xFF
        private const val WHITE: Byte = -1
        private const val LUMA_R = 299
        private const val LUMA_G = 587
        private const val LUMA_B = 114
        private const val LUMA_ROUND = 500
        private const val LUMA_DIV = 1000

        fun white(
            w: Int,
            h: Int,
        ) = Raster(w, h, ByteArray(w * h * CHANNELS) { WHITE })

        /** Builds a raster from a per-pixel packed 0xRRGGBB function (handy for tests). */
        fun of(
            w: Int,
            h: Int,
            pixel: (x: Int, y: Int) -> Int,
        ): Raster {
            val out = ByteArray(w * h * CHANNELS)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = pixel(x, y)
                    val i = (y * w + x) * CHANNELS
                    out[i] = (c shr 16).toByte()
                    out[i + 1] = (c shr 8).toByte()
                    out[i + 2] = c.toByte()
                }
            }
            return Raster(w, h, out)
        }
    }
}

/** round(x) = floor(x + 0.5) (ALGORITHMS 9.1). */
internal fun roundHalfUp(x: Double): Int = kotlin.math.floor(x + HALF).toInt()

private const val HALF = 0.5
