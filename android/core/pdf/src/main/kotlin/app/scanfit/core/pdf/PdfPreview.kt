package app.scanfit.core.pdf

import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.Resampler

/** A small picture of a page as written: white, with each image drawn at its place (display only, never exported). */
internal object PdfPreview {
    fun compose(
        size: PdfSize,
        images: List<Pair<Raster, PlacedPixels>>,
        longSide: Double,
    ): Raster {
        val scale = longSide / maxOf(size.width, size.height)
        val canvas = Raster.white(maxOf(1, roundHalfUp(size.width * scale)), maxOf(1, roundHalfUp(size.height * scale)))
        for ((raster, at) in images) {
            val x = roundHalfUp(at.rect.x * scale)
            val y = roundHalfUp(at.rect.y * scale)
            val w = maxOf(1, roundHalfUp(at.rect.width * scale))
            val h = maxOf(1, roundHalfUp(at.rect.height * scale))
            blit(canvas, Resampler.resize(raster, w, h), x, y)
        }
        return canvas
    }

    private fun blit(
        canvas: Raster,
        image: Raster,
        x: Int,
        y: Int,
    ) {
        for (row in 0 until image.height) {
            val cy = y + row
            if (cy !in 0 until canvas.height) continue
            val from = maxOf(0, -x)
            val to = minOf(image.width, canvas.width - x)
            if (from >= to) continue
            System.arraycopy(
                image.rgb,
                (row * image.width + from) * Raster.CHANNELS,
                canvas.rgb,
                (cy * canvas.width + x + from) * Raster.CHANNELS,
                (to - from) * Raster.CHANNELS,
            )
        }
    }
}
