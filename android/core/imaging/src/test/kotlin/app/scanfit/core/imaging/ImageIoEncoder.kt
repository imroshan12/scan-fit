package app.scanfit.core.imaging

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** JVM JPEG encoder for the pure-algorithm tests: baseline, quality 35-100, like the platform encoders. */
object ImageIoEncoder : JpegEncoder {
    override fun encode(
        raster: Raster,
        quality: Int,
    ): ByteArray {
        val img = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until raster.height) {
            for (x in 0 until raster.width) {
                img.setRGB(
                    x,
                    y,
                    (raster.r(x, y) shl 16) or (raster.g(x, y) shl 8) or raster.b(x, y),
                )
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
