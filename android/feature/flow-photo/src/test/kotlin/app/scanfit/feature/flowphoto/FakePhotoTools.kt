package app.scanfit.feature.flowphoto

import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.FitResult
import app.scanfit.core.imaging.FitStrategy
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.PipelineResult
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocSpec
import app.scanfit.core.testing.TestJpeg
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

        fun jpeg(
            width: Int,
            height: Int,
            size: Int,
        ): ByteArray = TestJpeg.make(width, height, size)
    }
}
