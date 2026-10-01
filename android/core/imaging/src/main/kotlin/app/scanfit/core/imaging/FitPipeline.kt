package app.scanfit.core.imaging

import app.scanfit.core.model.DocSpec

/** The cleanup applied before fitting (ALGORITHMS 9.9). PLAIN = crop (photos) or pad-to-aspect (everything else). */
enum class Pipeline(
    val ink: InkVariant?,
) {
    PLAIN(null),
    SIGNATURE_CLEANUP(InkVariant.SIGNATURE),
    THUMB_CLEANUP(InkVariant.THUMB),
    DOCUMENT_CLEANUP(InkVariant.DOCUMENT),
}

class PipelineResult(
    val fit: FitResult,
    /** The raster that went into the fit: cropped, or cleaned and padded to aspect. */
    val prepared: Raster,
    /** Present for the ink pipelines: the coverage gate result for the "too faint / too dark" warning. */
    val ink: InkResult?,
)

sealed interface PipelineOutcome {
    data class Success(
        val result: PipelineResult,
    ) : PipelineOutcome

    data class Failure(
        val error: FitError,
    ) : PipelineOutcome
}

/** crop/clean -> pad to aspect -> fit (ALGORITHMS sections 1.2, 3, 9.4-9.6). Pure given an injected encoder. */
class FitPipeline(
    encoder: JpegEncoder,
) {
    private val fit = FitEngine(encoder)

    fun run(
        source: Raster,
        spec: DocSpec,
        pipeline: Pipeline = Pipeline.PLAIN,
        crop: CropRect? = null,
        options: FitOptions = FitOptions(),
        ink: InkOptions = InkOptions(),
    ): PipelineOutcome {
        val aspect = Geometry.targetAspect(spec)
        var inkResult: InkResult? = null
        val prepared =
            when {
                pipeline.ink != null -> {
                    inkResult = InkCleanup.clean(source, checkNotNull(pipeline.ink), ink)
                    padded(inkResult.raster, aspect)
                }

                FitProfile.cropsToAspect(spec.type) -> {
                    val rect =
                        clampTo(source, crop ?: aspect?.let { Geometry.defaultCrop(source.width, source.height, it) })
                    rect?.let { source.crop(it.x, it.y, it.w, it.h) } ?: source
                }

                else -> {
                    padded(source, aspect)
                }
            }
        return when (val o = fit.fit(prepared, spec, options)) {
            is FitOutcome.Success -> PipelineOutcome.Success(PipelineResult(o.result, prepared, inkResult))
            is FitOutcome.Failure -> PipelineOutcome.Failure(o.error)
        }
    }

    private fun padded(
        r: Raster,
        aspect: Double?,
    ): Raster {
        if (aspect == null) return r
        val plan = Geometry.padToAspect(r.width, r.height, aspect)
        return if (plan.w == r.width && plan.h == r.height) r else r.crop(-plan.x, -plan.y, plan.w, plan.h)
    }

    private fun clampTo(
        r: Raster,
        c: CropRect?,
    ): CropRect? {
        if (c == null) return null
        val x = c.x.coerceIn(0, r.width - 1)
        val y = c.y.coerceIn(0, r.height - 1)
        return CropRect(x, y, c.w.coerceIn(1, r.width - x), c.h.coerceIn(1, r.height - y))
    }
}

/** Maps a crop given in original-source pixels onto a downsampled raster (ALGORITHMS 9.9). */
fun CropRect.scaledTo(
    srcW: Int,
    srcH: Int,
    decoded: Raster,
): CropRect {
    val sx = decoded.width.toDouble() / srcW
    val sy = decoded.height.toDouble() / srcH
    return CropRect(
        roundHalfUp(x * sx),
        roundHalfUp(y * sy),
        maxOf(1, roundHalfUp(w * sx)),
        maxOf(1, roundHalfUp(h * sy)),
    )
}
