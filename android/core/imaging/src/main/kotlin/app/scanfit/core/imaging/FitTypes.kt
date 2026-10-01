package app.scanfit.core.imaging

/** Remote Config `min_fill_strategy` (ALGORITHMS 1.4). */
enum class MinFillStrategy { UPSCALE_THEN_PAD, PAD_ONLY }

/** How the final file reached its window. `wire` is the analytics `fit_strategy` value. */
enum class FitStrategy(
    val wire: String,
) {
    QUALITY_SEARCH("quality_search"),
    DOWNSCALE("downscale"),
    UPSCALE("upscale"),
    PAD("pad"),
    UPSCALE_THEN_PAD("upscale_then_pad"),
}

enum class FitError {
    /** Cannot get under the maximum even at the lowest quality and smallest allowed size. */
    TOO_DETAILED,

    /** The slot has no maximum size, so there is nothing to fit to (the spec is not verified yet). */
    UNKNOWN_LIMIT,

    /** The slot does not accept JPEG (PDF slots use the PDF engine). */
    UNSUPPORTED_FORMAT,

    /** The encoder produced bytes the patcher refused (a pipeline bug, surfaced instead of exported). */
    ENCODING_FAILED,
}

data class FitOptions(
    val minFill: MinFillStrategy = MinFillStrategy.UPSCALE_THEN_PAD,
    val allowGrayscale: Boolean = false,
)

data class FitResult(
    /** The final, patched (and possibly padded) JPEG, ready to write. */
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    /** JPEG quality used (35-100). */
    val quality: Int,
    val strategy: FitStrategy,
    val encodes: Int,
) {
    val kb: Double get() = bytes.size / BYTES_PER_KB

    override fun equals(other: Any?) = other is FitResult && bytes.contentEquals(other.bytes) && width == other.width &&
        height == other.height && quality == other.quality && strategy == other.strategy && encodes == other.encodes

    override fun hashCode() = bytes.contentHashCode() * HASH + width * HASH + height

    private companion object {
        const val BYTES_PER_KB = 1024.0
        const val HASH = 31
    }
}

sealed interface FitOutcome {
    data class Success(
        val result: FitResult,
    ) : FitOutcome

    data class Failure(
        val error: FitError,
    ) : FitOutcome
}

/** Produces JPEG bytes for a raster at a quality (35-100). Platform encoders (and test encoders) implement this. */
fun interface JpegEncoder {
    fun encode(
        raster: Raster,
        quality: Int,
    ): ByteArray
}
