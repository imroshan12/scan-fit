package app.scanfit.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Android text engine for the strip: measures and draws with `Paint` (system sans, Roboto on device). */
object AndroidStripRenderer : TextMeasurer {
    private fun paint(size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    override fun width(text: String, sizePx: Float, bold: Boolean): Float = paint(sizePx, bold).measureText(text)

    /** Draws [layout]'s lines onto [base] (black text) and returns the result. */
    fun draw(base: Raster, layout: StripLayout): Raster {
        val pixels = IntArray(base.width * base.height) {
            val i = it * Raster.CHANNELS
            Color.rgb(base.rgb[i].toInt() and BYTE, base.rgb[i + 1].toInt() and BYTE, base.rgb[i + 2].toInt() and BYTE)
        }
        // createBitmap(pixels, ...) is immutable and Canvas needs a mutable bitmap, so copy the pixels in.
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        val canvas = Canvas(bitmap)
        for (line in layout.lines) {
            canvas.drawText(line.text, line.centerX, line.baselineY, paint(line.sizePx, line.bold))
        }
        val out = IntArray(base.width * base.height)
        bitmap.getPixels(out, 0, base.width, 0, 0, base.width, base.height)
        bitmap.recycle()
        return Raster(
            base.width,
            base.height,
            ByteArray(out.size * 3) { i ->
                val c = out[i / 3]
                when (i % 3) {
                    0 -> (c shr 16).toByte()
                    1 -> (c shr 8).toByte()
                    else -> c.toByte()
                }
            },
        )
    }

    /** Photo + strip in one step: the output has the same size as [photo]. */
    fun withStrip(photo: Raster, name: String, date: String): Raster {
        val layout = NameDateStrip.layout(name, date, photo.width, photo.height, this)
        return draw(NameDateStrip.placePhoto(photo, layout, photo.width, photo.height), layout)
    }

    private const val BYTE = 0xFF
}
