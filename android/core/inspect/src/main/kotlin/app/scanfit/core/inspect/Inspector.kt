package app.scanfit.core.inspect

/**
 * Reads a file's facts from its bytes (ALGORITHMS section 5 / 9.2). The format comes from magic bytes, never the
 * extension. Pure Kotlin, no Android imports; malformed input never throws.
 */
object Inspector {
    private const val HEIC_BRAND_AT = 8
    private const val FTYP_AT = 4
    private const val WEBP_AT = 8
    private const val ISO_BOX_MIN = 12
    private const val PDF_MAGIC = "%PDF-"
    private val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF)
    private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47)
    private const val PNG_IHDR_WIDTH = 16
    private const val PNG_COLOR_TYPE = 25
    private const val PNG_MIN_HEADER = 26
    private val HEIC_BRANDS = setOf("heic", "heix", "mif1", "heim", "heis")
    private val EXT_FORMAT =
        mapOf(
            "jpg" to DetectedFormat.JPEG,
            "jpeg" to DetectedFormat.JPEG,
            "png" to DetectedFormat.PNG,
            "pdf" to DetectedFormat.PDF,
            "heic" to DetectedFormat.HEIC,
            "heif" to DetectedFormat.HEIC,
            "webp" to DetectedFormat.WEBP,
        )

    fun detectFormat(b: ByteArray): DetectedFormat = when {
        b.startsWith(*JPEG_MAGIC) -> DetectedFormat.JPEG
        b.startsWith(*PNG_MAGIC) -> DetectedFormat.PNG
        b.ascii(0, PDF_MAGIC.length) == PDF_MAGIC -> DetectedFormat.PDF
        isHeic(b) -> DetectedFormat.HEIC
        b.size >= ISO_BOX_MIN && b.ascii(0, 4) == "RIFF" && b.ascii(WEBP_AT, 4) == "WEBP" -> DetectedFormat.WEBP
        else -> DetectedFormat.UNKNOWN
    }

    private fun isHeic(b: ByteArray): Boolean {
        if (b.size < ISO_BOX_MIN || b.ascii(FTYP_AT, 4) != "ftyp") return false
        return b.ascii(HEIC_BRAND_AT, 4) in HEIC_BRANDS
    }

    fun inspect(
        bytes: ByteArray,
        fileName: String = "",
    ): InspectedFile {
        val format = detectFormat(bytes)
        val base =
            when (format) {
                DetectedFormat.JPEG -> JpegInspector.inspect(bytes)
                DetectedFormat.PNG -> inspectPng(bytes)
                DetectedFormat.PDF -> PdfInspector.inspect(bytes)
                else -> InspectedFile(format, bytes.size)
            }
        return base.copy(issues = contextFreeIssues(base, fileName))
    }

    private fun contextFreeIssues(
        f: InspectedFile,
        fileName: String,
    ): List<Issue> = buildList {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val named = EXT_FORMAT[ext]
        if (named != null && named != f.format) add(Issue.EXTENSION_MISMATCH)
        if (f.format == DetectedFormat.JPEG) {
            if (f.color == ColorKind.CMYK) add(Issue.CMYK_COLOR)
            if (f.progressive) add(Issue.PROGRESSIVE_JPEG)
            if (f.hasGps) add(Issue.HAS_GPS_EXIF)
            if ((f.exifOrientation ?: 1) != 1) add(Issue.ROTATED_BY_EXIF)
        }
        if (f.format == DetectedFormat.HEIC) add(Issue.HEIC_NOT_ACCEPTED)
        if (f.format == DetectedFormat.PDF && f.pdfEncrypted) add(Issue.PDF_ENCRYPTED)
    }

    private fun inspectPng(b: ByteArray): InspectedFile {
        if (b.size < PNG_MIN_HEADER) return InspectedFile(DetectedFormat.PNG, b.size)
        val color =
            when (b.u8(PNG_COLOR_TYPE)) {
                0, 4 -> ColorKind.GRAY
                2, 3, 6 -> ColorKind.RGB
                else -> ColorKind.UNKNOWN
            }
        return InspectedFile(
            format = DetectedFormat.PNG,
            bytes = b.size,
            width = b.u32be(PNG_IHDR_WIDTH).toInt(),
            height = b.u32be(PNG_IHDR_WIDTH + 4).toInt(),
            color = color,
        )
    }
}
