package app.scanfit.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RobolectricSpikeTest {
    private fun noisy(
        w: Int,
        h: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var seed = 12345
        for (y in 0 until h) {
            for (x in 0 until w) {
                seed = seed * 1103515245 + 12345
                val v = (seed ushr 16) and 0xFF
                bmp.setPixel(x, y, Color.rgb(v, (x * 255) / w, (y * 255) / h))
            }
        }
        return bmp
    }

    private fun jpeg(
        bmp: Bitmap,
        q: Int,
    ): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    @Test
    fun nativeGraphicsEncodesRealJpegsWhoseSizeFollowsQuality() {
        val bmp = noisy(200, 230)
        val sizes = listOf(35, 65, 95).map { jpeg(bmp, it).size }
        println("SPIKE5 sizes at q35/65/95 = $sizes")
        assertTrue("size must grow with quality: $sizes", sizes[0] < sizes[1] && sizes[1] < sizes[2])
        val bytes = jpeg(bmp, 80)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(200, decoded.width)
        assertEquals(230, decoded.height)
    }
}
