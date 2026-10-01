package app.scanfit.core.inspect

enum class DetectedFormat { JPEG, PNG, PDF, HEIC, WEBP, UNKNOWN }

enum class ColorKind { RGB, GRAY, CMYK, UNKNOWN }

/** Issue codes from ALGORITHMS section 5. Slot-specific ones are produced by the match engine (section 9.7). */
enum class Issue {
    EXTENSION_MISMATCH,
    CMYK_COLOR,
    PROGRESSIVE_JPEG,
    HAS_GPS_EXIF,
    ROTATED_BY_EXIF,
    HEIC_NOT_ACCEPTED,
    PDF_ENCRYPTED,
    TOO_SMALL_KB,
    TOO_LARGE_KB,
    WRONG_DIMENSIONS,
    WRONG_ASPECT,
    LOW_DPI_METADATA,
    GRAYSCALE_NOT_ALLOWED,
}

/** JFIF density as stored: `units` 0 = aspect only, 1 = dots/inch, 2 = dots/cm. */
data class JfifDensity(
    val units: Int,
    val xDensity: Int,
    val yDensity: Int,
)

/** Everything the inspector learned about a file (ALGORITHMS 9.2). Fields that do not apply are null/false. */
data class InspectedFile(
    val format: DetectedFormat,
    val bytes: Int,
    val width: Int? = null,
    val height: Int? = null,
    val color: ColorKind = ColorKind.UNKNOWN,
    val components: Int? = null,
    val progressive: Boolean = false,
    val sof: String? = null,
    val jfif: JfifDensity? = null,
    val exifOrientation: Int? = null,
    val hasExif: Boolean = false,
    val hasGps: Boolean = false,
    val hasIcc: Boolean = false,
    val hasXmp: Boolean = false,
    val hasAdobe: Boolean = false,
    val pdfPages: Int? = null,
    val pdfEncrypted: Boolean = false,
    val pdfImageOnly: Boolean = false,
    val issues: List<Issue> = emptyList(),
) {
    val kb: Double get() = bytes / BYTES_PER_KB

    private companion object {
        const val BYTES_PER_KB = 1024.0
    }
}
