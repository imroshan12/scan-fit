package app.scanfit.feature.flowphoto

import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.FitResult
import app.scanfit.core.imaging.FitStrategy
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.PipelineResult
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocSpec
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Test double for [PhotoTools]: files by URI, a fixed decoded raster, and a configurable fit. */
class FakePhotoTools : PhotoTools {
    val files = mutableMapOf<String, ByteArray>()
    var decoded: Raster? = Raster.of(1000, 1400) { x, y -> if (x < 500) 0x336699 else (y and 0xFF) }
    var discarded = 0
        private set
    val strips = mutableListOf<Pair<String, String>>()
    var fitInput: Raster? = null
        private set
    var fit: (Raster) -> PipelineOutcome = { r -> success(r, jpeg(200, 230, 34 * 1024)) }

    override suspend fun read(uri: String): ByteArray? = files[uri]

    override fun newCaptureUri(): String = "content://test.photoflow.files/capture/1.jpg"

    override fun discardCaptures() {
        discarded++
    }

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = decoded

    override fun drawStrip(
        photo: Raster,
        name: String,
        date: String,
    ): Raster {
        strips += name to date
        return photo
    }

    override fun fit(
        prepared: Raster,
        spec: DocSpec,
    ): PipelineOutcome {
        fitInput = prepared
        return fit(prepared)
    }

    override fun today(): LocalDate = LocalDate.of(2026, 10, 3)

    companion object {
        fun success(
            prepared: Raster,
            bytes: ByteArray,
        ): PipelineOutcome = PipelineOutcome.Success(
            PipelineResult(FitResult(bytes, 0, 0, 90, FitStrategy.QUALITY_SEARCH, 3), prepared, null),
        )

        fun failure(error: FitError): PipelineOutcome = PipelineOutcome.Failure(error)

        /**
         * A structurally valid baseline JPEG (SOI, JFIF, SOF0 with 3 components, SOS, EOI) of exactly [size] bytes, so the
         * real Inspector and match engine judge it like a fitted file. COM segments fill it up, as the fit engine's pad does.
         */
        fun jpeg(
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
                    0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(),
                    width.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1,
                ),
            )
            val tail = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0, 0x55, 0xFF.toByte(), 0xD9.toByte())
            var gap = size - head.size() - tail.size
            require(gap == 0 || gap >= 4) { "size $size cannot be padded exactly" }
            while (gap > 0) {
                val n = minOf(gap, 65_537).let { if (gap - it in 1..3) it - (4 - (gap - it)) else it }
                val payload = n - 4
                head.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), ((payload + 2) shr 8).toByte(), (payload + 2).toByte()))
                head.write(ByteArray(payload) { 0x20 })
                gap -= n
            }
            head.write(tail)
            return head.toByteArray()
        }
    }
}
