package app.scanfit.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.scanfit.core.inspect.Inspector
import java.io.ByteArrayOutputStream

/**
 * Production JPEG encoder: Skia via `Bitmap.compress` (baseline, libjpeg-turbo).
 * The patcher strips whatever metadata it adds.
 */
object AndroidJpegEncoder : JpegEncoder {
    override fun encode(
        raster: Raster,
        quality: Int,
    ): ByteArray {
        val bitmap = raster.toBitmap()
        return try {
            ByteArrayOutputStream()
                .also {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "JPEG encode failed" }
                }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}

/** An opaque ARGB_8888 bitmap with the raster's pixels (for encoders, ML models and on-screen previews). */
fun Raster.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = Color.rgb(r(x, y), g(x, y), b(x, y))
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Production decoder (ALGORITHMS 1.1): subsamples on decode so a full-resolution bitmap is never created,
 * applies the EXIF orientation (BitmapFactory does not), converts to 8-bit sRGB and flattens alpha onto white.
 */
object AndroidImageDecoder {
    private const val MIN_CAP = 1600
    private const val CAP_FACTOR = 2

    /** The cap for the decoded long side given the largest target dimension (ALGORITHMS 1.1 step 2). */
    fun longSideCap(largestTargetDimension: Int): Int = maxOf(CAP_FACTOR * largestTargetDimension, MIN_CAP)

    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = Inspector.inspect(bytes).exifOrientation ?: 1
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        val target = minOf(maxLongSide, long)
        // Largest power-of-two subsample that still leaves at least `target` pixels on the long side,
        // then an exact resize.
        var sample = 1
        while (long / (sample * 2) >= target) sample *= 2
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val raster =
            try {
                toRaster(bitmap)
            } finally {
                bitmap.recycle()
            }
        val oriented = Orientation.apply(raster, orientation)
        val swapped = orientation in 5..8
        val ow = if (swapped) bounds.outHeight else bounds.outWidth
        val oh = if (swapped) bounds.outWidth else bounds.outHeight
        val scale = target.toDouble() / long
        val wantW =
            if (target == long) {
                ow
            } else if (ow >= oh) {
                target
            } else {
                maxOf(1, roundHalfUp(ow * scale))
            }
        val wantH =
            if (target == long) {
                oh
            } else if (oh > ow) {
                target
            } else {
                maxOf(1, roundHalfUp(oh * scale))
            }
        return if (oriented.width == wantW &&
            oriented.height == wantH
        ) {
            oriented
        } else {
            Resampler.resize(oriented, wantW, wantH)
        }
    }

    private fun toRaster(bitmap: Bitmap): Raster {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * Raster.CHANNELS)
        for (i in argb.indices) {
            val c = argb[i]
            val a = c ushr ALPHA_SHIFT
            // flatten alpha onto white: out = c*a + 255*(1-a)
            out[i * 3] = flatten((c shr RED_SHIFT) and BYTE, a)
            out[i * 3 + 1] = flatten((c shr GREEN_SHIFT) and BYTE, a)
            out[i * 3 + 2] = flatten(c and BYTE, a)
        }
        return Raster(w, h, out)
    }

    private fun flatten(
        channel: Int,
        alpha: Int,
    ): Byte {
        if (alpha == BYTE) return channel.toByte()
        return ((channel * alpha + BYTE * (BYTE - alpha) + HALF_BYTE) / BYTE).toByte()
    }

    private const val BYTE = 0xFF
    private const val HALF_BYTE = 127
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
