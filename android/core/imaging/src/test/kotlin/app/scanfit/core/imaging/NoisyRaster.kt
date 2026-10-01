package app.scanfit.core.imaging

/** Deterministic noisy picture: detail (and so JPEG size) is controllable with [noise]. */
fun noisyRaster(
    w: Int,
    h: Int,
    noise: Int = 60,
    seed: Int = 7,
): Raster {
    var s = seed
    return Raster.of(w, h) { x, y ->
        s = s * 1_103_515_245 + 12_345
        val n = ((s ushr 16) and 0xFF) * noise / 255
        val r = ((x * 255) / w + n).coerceIn(0, 255)
        val g = ((y * 255) / h + n).coerceIn(0, 255)
        val b = (((x + y) * 127) / (w + h) + n).coerceIn(0, 255)
        (r shl 16) or (g shl 8) or b
    }
}
