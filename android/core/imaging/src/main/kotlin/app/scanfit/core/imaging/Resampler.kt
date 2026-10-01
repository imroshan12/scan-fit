package app.scanfit.core.imaging

/**
 * High-quality separable resize: exact area averaging when shrinking an axis, bilinear when enlarging it.
 * Written once in plain code (and mirrored in Swift) so both platforms resample alike and the engines
 * need no platform graphics API.
 */
object Resampler {
    private const val HALF = 0.5f
    private const val MAX_BYTE = 255

    fun resize(
        src: Raster,
        w: Int,
        h: Int,
    ): Raster {
        if (w == src.width && h == src.height) return src
        val horizontal = if (w == src.width) null else axisWeights(src.width, w)
        val vertical = if (h == src.height) null else axisWeights(src.height, h)
        val mid = if (horizontal == null) toFloats(src) else passHorizontal(src, horizontal, w)
        return if (vertical == null) toRaster(mid, w, src.height) else passVertical(mid, w, src.height, vertical, h)
    }

    /** For each destination index: first source index plus the weights of the consecutive source pixels it covers. */
    private class Weights(
        val first: IntArray,
        val weights: Array<FloatArray>,
    )

    private fun axisWeights(
        srcN: Int,
        dstN: Int,
    ): Weights {
        val first = IntArray(dstN)
        val weights = Array(dstN) { FloatArray(0) }
        val scale = srcN.toDouble() / dstN
        for (d in 0 until dstN) {
            if (scale > 1.0) { // shrinking: the destination pixel covers [d*scale, (d+1)*scale) of the source
                val lo = d * scale
                val hi = (d + 1) * scale
                val i0 = lo.toInt()
                val i1 = minOf(srcN - 1, kotlin.math.ceil(hi).toInt() - 1)
                val w = FloatArray(i1 - i0 + 1)
                var sum = 0f
                for (i in i0..i1) {
                    val cover = (minOf(hi, i + 1.0) - maxOf(lo, i.toDouble())).toFloat()
                    w[i - i0] = cover
                    sum += cover
                }
                for (k in w.indices) w[k] /= sum
                first[d] = i0
                weights[d] = w
            } else { // enlarging: bilinear around the centre of the destination pixel
                val pos = (d + HALF.toDouble()) * scale - HALF
                val i0 = kotlin.math.floor(pos).toInt()
                val frac = (pos - i0).toFloat()
                val a = i0.coerceIn(0, srcN - 1)
                val b = (i0 + 1).coerceIn(0, srcN - 1)
                if (a == b) {
                    first[d] = a
                    weights[d] = floatArrayOf(1f)
                } else {
                    first[d] = a
                    weights[d] = floatArrayOf(1f - frac, frac)
                }
            }
        }
        return Weights(first, weights)
    }

    private fun toFloats(src: Raster) = FloatArray(src.rgb.size) { (src.rgb[it].toInt() and MAX_BYTE).toFloat() }

    private fun passHorizontal(
        src: Raster,
        wts: Weights,
        dstW: Int,
    ): FloatArray {
        val out = FloatArray(dstW * src.height * Raster.CHANNELS)
        for (y in 0 until src.height) {
            for (x in 0 until dstW) {
                val w = wts.weights[x]
                val from = wts.first[x]
                for (c in 0 until Raster.CHANNELS) {
                    var acc = 0f
                    for (k in w.indices) {
                        acc +=
                            w[k] * (src.rgb[((y * src.width) + from + k) * Raster.CHANNELS + c].toInt() and MAX_BYTE)
                    }
                    out[(y * dstW + x) * Raster.CHANNELS + c] = acc
                }
            }
        }
        return out
    }

    private fun passVertical(
        mid: FloatArray,
        w: Int,
        srcH: Int,
        wts: Weights,
        dstH: Int,
    ): Raster {
        require(srcH > 0)
        val out = ByteArray(w * dstH * Raster.CHANNELS)
        for (y in 0 until dstH) {
            val wy = wts.weights[y]
            val from = wts.first[y]
            for (x in 0 until w) {
                for (c in 0 until Raster.CHANNELS) {
                    var acc = 0f
                    for (k in wy.indices) acc += wy[k] * mid[((from + k) * w + x) * Raster.CHANNELS + c]
                    out[(y * w + x) * Raster.CHANNELS + c] = clamp(acc)
                }
            }
        }
        return Raster(w, dstH, out)
    }

    private fun toRaster(
        mid: FloatArray,
        w: Int,
        h: Int,
    ) = Raster(w, h, ByteArray(mid.size) { clamp(mid[it]) })

    private fun clamp(v: Float): Byte = (v + HALF).toInt().coerceIn(0, MAX_BYTE).toByte()
}
