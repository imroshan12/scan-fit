package app.scanfit.feature.flowink

import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.FitResult
import app.scanfit.core.imaging.FitStrategy
import app.scanfit.core.imaging.InkOptions
import app.scanfit.core.imaging.InkQuality
import app.scanfit.core.imaging.InkResult
import app.scanfit.core.imaging.Pipeline
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.PipelineResult
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocSpec
import app.scanfit.core.testing.TestJpeg

/** Test double for [InkTools]: files by URI, a fixed decoded raster, and a configurable cleanup + fit. */
class FakeInkTools : InkTools {
    val files = mutableMapOf<String, ByteArray>()
    var decoded: Raster? = Raster.of(1200, 800) { x, y -> if (x in 300..900 && y in 300..500) 0x202040 else 0xF0EEE8 }
    var discarded = 0
        private set
    var quality = InkQuality.OK
    var fitInput: Raster? = null
        private set
    val fits = mutableListOf<Pair<Pipeline, InkOptions>>()
    var fit: (Raster) -> PipelineOutcome = { r -> success(r, TestJpeg.make(140, 60, 16 * 1024)) }

    override suspend fun read(uri: String): ByteArray? = files[uri]

    override fun newCaptureUri(): String = "content://test.capture/1.jpg"

    override fun discardCaptures() {
        discarded++
    }

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = decoded

    override fun fit(
        cropped: Raster,
        spec: DocSpec,
        pipeline: Pipeline,
        ink: InkOptions,
    ): PipelineOutcome {
        fitInput = cropped
        fits += pipeline to ink
        return fit(cropped)
    }

    fun success(
        prepared: Raster,
        bytes: ByteArray,
    ): PipelineOutcome = PipelineOutcome.Success(
        PipelineResult(FitResult(bytes, 0, 0, 90, FitStrategy.QUALITY_SEARCH, 3), prepared, InkResult(prepared, quality, 0.05)),
    )

    fun failure(error: FitError): PipelineOutcome = PipelineOutcome.Failure(error)
}
