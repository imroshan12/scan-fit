package app.scanfit.core.imaging

import kotlin.math.sqrt

/** Plane operations for ink cleanup (ALGORITHMS 9.5). Planes are row-major IntArrays of 0..255 (or 0/1 for masks). */
internal object ImageOps {
    private const val PASSES = 3
    private const val TWELVE = 12.0

    /** Widths of the three box blurs that approximate a Gaussian of [sigma] ("boxes for Gauss", ALGORITHMS 9.5). */
    fun boxWidths(sigma: Double): IntArray {
        val ideal = sqrt(TWELVE * sigma * sigma / PASSES + 1)
        var wl = kotlin.math.floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        wl = maxOf(wl, 1)
        val wu = wl + 2
        val m =
            roundHalfUp(
                (TWELVE * sigma * sigma - PASSES * wl * wl - 12.0 * wl - 9.0) / (-4.0 * wl - 4.0),
            ).coerceIn(0, PASSES)
        return IntArray(PASSES) { if (it < m) wl else wu }
    }

    fun gaussianApprox(
        plane: IntArray,
        w: Int,
        h: Int,
        sigma: Double,
    ): IntArray {
        var cur = plane
        for (width in boxWidths(sigma)) {
            if (width <= 1) continue
            cur = boxBlurVertical(boxBlurHorizontal(cur, w, h, width), w, h, width)
        }
        return cur
    }

    private fun boxBlurHorizontal(
        src: IntArray,
        w: Int,
        h: Int,
        width: Int,
    ): IntArray {
        val r = (width - 1) / 2
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                out[row + x] = (sum + width / 2) / width
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        return out
    }

    private fun boxBlurVertical(
        src: IntArray,
        w: Int,
        h: Int,
        width: Int,
    ): IntArray {
        val r = (width - 1) / 2
        val out = IntArray(src.size)
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum + width / 2) / width
                sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** Sauvola threshold (ALGORITHMS 9.5): 1 where the pixel is ink. Window clipped at the borders. */
    fun sauvola(
        n: IntArray,
        w: Int,
        h: Int,
        window: Int,
        k: Double,
        range: Double,
    ): IntArray {
        val sum = LongArray((w + 1) * (h + 1))
        val sq = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            var rowSq = 0L
            for (x in 0 until w) {
                val v = n[y * w + x].toLong()
                rowSum += v
                rowSq += v * v
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + rowSum
                sq[(y + 1) * (w + 1) + x + 1] = sq[y * (w + 1) + x + 1] + rowSq
            }
        }
        val r = window / 2
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w - 1, x + r)
                val count = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()
                val s = rect(sum, w + 1, x0, y0, x1, y1).toDouble()
                val s2 = rect(sq, w + 1, x0, y0, x1, y1).toDouble()
                val mean = s / count
                val sd = sqrt(maxOf(s2 / count - mean * mean, 0.0))
                val threshold = mean * (1 + k * (sd / range - 1))
                if (n[y * w + x] < threshold) out[y * w + x] = 1
            }
        }
        return out
    }

    private fun rect(
        table: LongArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Long = table[(y1 + 1) * stride + x1 + 1] - table[y0 * stride + x1 + 1] - table[(y1 + 1) * stride + x0] +
        table[y0 * stride + x0]

    /** 3x3 binary opening; erosion treats outside as ink, dilation as background (ALGORITHMS 9.5). */
    fun open3x3(
        mask: IntArray,
        w: Int,
        h: Int,
    ): IntArray = dilate(erode(mask, w, h), w, h)

    private fun erode(
        m: IntArray,
        w: Int,
        h: Int,
    ): IntArray {
        val out = IntArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var all = 1
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        if (m[ny * w + nx] == 0) {
                            all = 0
                            break@loop
                        }
                    }
                }
                out[y * w + x] = all
            }
        }
        return out
    }

    private fun dilate(
        m: IntArray,
        w: Int,
        h: Int,
    ): IntArray {
        val out = IntArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var any = 0
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        if (m[ny * w + nx] == 1) {
                            any = 1
                            break@loop
                        }
                    }
                }
                out[y * w + x] = any
            }
        }
        return out
    }

    /** Removes 8-connected components smaller than [minSize] pixels. */
    fun removeSpecks(
        mask: IntArray,
        w: Int,
        h: Int,
        minSize: Int,
    ): IntArray {
        val out = mask.copyOf()
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (mask[start] == 0 || seen[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            seen[start] = true
            val members = ArrayList<Int>()
            while (top > 0) {
                val p = stack[--top]
                size++
                if (size <= minSize) members += p
                val px = p % w
                val py = p / w
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (mask[q] == 1 && !seen[q]) {
                            seen[q] = true
                            stack[top++] = q
                        }
                    }
                }
            }
            if (size < minSize) members.forEach { out[it] = 0 }
        }
        return out
    }
}
