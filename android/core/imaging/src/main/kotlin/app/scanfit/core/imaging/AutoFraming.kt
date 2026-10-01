package app.scanfit.core.imaging

/** A detected face in raster pixels (kept here so the pure framing math has no dependency on the ML wrappers). */
data class Face(val x: Double, val y: Double, val w: Double, val h: Double) {
    val centerX: Double get() = x + w / 2
}

class Framing(val crop: CropRect, val coverageAdjusted: Boolean)

/** Face-aware crop (ALGORITHMS 2.2 / 9.6): face 60-75 % of the height, centred, crown 10 % from the top. */
object AutoFraming {
    const val DEFAULT_COVERAGE = 0.675
    private const val CHIN_TO_HAIRLINE = 1.25
    private const val CROWN_ABOVE_FACE = 0.125
    private const val CROWN_FROM_TOP = 0.10

    /**
     * @param aspect output width / height of the slot
     * @param coverage share of the crop height taken by the chin-to-hairline span (a preset `face_coverage` overrides)
     */
    fun frame(face: Face, imgW: Int, imgH: Int, aspect: Double, coverage: Double = DEFAULT_COVERAGE): Framing {
        require(imgW > 0 && imgH > 0 && aspect > 0 && coverage > 0)
        var h = roundHalfUp(CHIN_TO_HAIRLINE * face.h / coverage)
        var w = roundHalfUp(h * aspect)
        var shrunk = false
        if (w > imgW || h > imgH) {
            val s = minOf(imgW.toDouble() / w, imgH.toDouble() / h)
            h = maxOf(1, kotlin.math.floor(h * s).toInt())
            w = roundHalfUp(h * aspect)
            if (w > imgW) {
                w = imgW
                h = maxOf(1, roundHalfUp(w / aspect))
            }
            shrunk = true
        }
        val crown = face.y - CROWN_ABOVE_FACE * face.h
        val x = roundHalfUp(face.x + face.w / 2 - w / 2.0).coerceIn(0, imgW - w)
        val y = roundHalfUp(crown - CROWN_FROM_TOP * h).coerceIn(0, imgH - h)
        return Framing(CropRect(x, y, w, h), shrunk)
    }
}
