@file:Suppress("MagicNumber") // JPEG marker and header bytes, spelled out as the format defines them

package app.scanfit.core.testing

import java.io.ByteArrayOutputStream

/** Valid fitted-file stand-ins for tests that inspect or verify bytes. */
object TestJpeg {
    /**
     * A structurally valid baseline JPEG (SOI, JFIF, SOF0 with 3 components, SOS, EOI) of exactly [size] bytes, so the
     * real Inspector and match engine judge it like a fitted file. COM segments fill it up, as the fit engine's pad
     * does.
     */
    fun make(
        width: Int,
        height: Int,
        size: Int,
    ): ByteArray {
        val head = ByteArrayOutputStream()
        head.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        head.write(
            byteArrayOf(
                0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(),
                'F'.code.toByte(), 0, 1, 1, 1, 0, 200.toByte(), 0, 200.toByte(), 0, 0,
            ),
        )
        head.write(
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(), 0, 17, 8,
                (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(),
                width.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
            ),
        )
        val tail =
            byteArrayOf(
                0xFF.toByte(), 0xDA.toByte(), 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0, 0x55,
                0xFF.toByte(), 0xD9.toByte(),
            )
        var gap = size - head.size() - tail.size
        require(gap == 0 || gap >= 4) { "size $size cannot be padded exactly" }
        while (gap > 0) {
            val n = minOf(gap, 65_537).let { if (gap - it in 1..3) it - (4 - (gap - it)) else it }
            val payload = n - 4
            val length = payload + 2
            head.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), (length shr 8).toByte(), length.toByte()))
            head.write(ByteArray(payload) { 0x20 })
            gap -= n
        }
        head.write(tail)
        return head.toByteArray()
    }
}
