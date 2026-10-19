package app.scanfit.core.imaging

/**
 * The picture My Kit keeps for reuse (ALGORITHMS 13.1): the flow's upright working source, at most 2400 px on the long
 * side, as a baseline JPEG at quality 92 with metadata stripped by the patcher. Pure: the encoder is passed in.
 */
object KitOriginalImage {
    const val MAX_LONG_SIDE = 2400
    const val QUALITY = 92

    /** The stored size for a [width] × [height] source (`kit_size_cases`). */
    fun storedSize(
        width: Int,
        height: Int,
    ): Pair<Int, Int> = when {
        maxOf(width, height) <= MAX_LONG_SIDE -> width to height
        width >= height -> MAX_LONG_SIDE to maxOf(1, roundHalfUp(height.toDouble() * MAX_LONG_SIDE / width))
        else -> maxOf(1, roundHalfUp(width.toDouble() * MAX_LONG_SIDE / height)) to MAX_LONG_SIDE
    }

    /** The JPEG to keep, or `null` when encoding or patching failed. */
    fun encode(
        raster: Raster,
        encoder: JpegEncoder,
    ): ByteArray? {
        val (w, h) = storedSize(raster.width, raster.height)
        val sized = if (w == raster.width && h == raster.height) raster else Resampler.resize(raster, w, h)
        return (JpegPatcher.patch(encoder.encode(sized, QUALITY)) as? PatchResult.Ok)?.bytes
    }
}
