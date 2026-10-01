package app.scanfit.core.inspect

internal object JpegInspector {
    private const val JFIF_ID_LENGTH = 5
    private const val JFIF_UNITS_AT = 7
    private const val SOF_PRECISION_BYTES = 1
    private const val SOF_COMPONENTS_AT = 5
    private val PROGRESSIVE_SOFS = setOf(0xC2, 0xC6, 0xCA, 0xCE)

    fun inspect(b: ByteArray): InspectedFile {
        val walk = JpegWalk.of(b)
        var f = InspectedFile(DetectedFormat.JPEG, b.size)
        for (seg in walk.segments) {
            val body = seg.bodyStart
            f =
                when {
                    seg.marker == JpegSegment.APP0 && b.ascii(body, JFIF_ID_LENGTH) == "JFIF\u0000" -> {
                        f.copy(
                            jfif =
                            JfifDensity(
                                b.u8(body + JFIF_UNITS_AT),
                                b.u16(body + JFIF_UNITS_AT + 1),
                                b.u16(body + JFIF_UNITS_AT + 3),
                            ),
                        )
                    }

                    seg.marker == JpegSegment.APP1 && b.ascii(body, EXIF_ID.length) == EXIF_ID -> {
                        val exif = ExifReader.read(b, body, seg.end)
                        f.copy(hasExif = true, exifOrientation = exif.orientation, hasGps = exif.hasGps)
                    }

                    seg.marker == JpegSegment.APP1 && b.ascii(body, XMP_PREFIX.length) == XMP_PREFIX -> {
                        f.copy(hasXmp = true)
                    }

                    seg.marker == JpegSegment.APP2 && b.ascii(body, ICC_ID.length) == ICC_ID -> {
                        f.copy(hasIcc = true)
                    }

                    seg.marker == JpegSegment.APP14 && b.ascii(body, ADOBE_ID.length) == ADOBE_ID -> {
                        f.copy(hasAdobe = true)
                    }

                    seg.isSof && body + SOF_COMPONENTS_AT < b.size -> {
                        sof(f, b, seg)
                    }

                    else -> {
                        f
                    }
                }
        }
        return f
    }

    private fun sof(
        f: InspectedFile,
        b: ByteArray,
        seg: JpegSegment,
    ): InspectedFile {
        val body = seg.bodyStart
        val components = b.u8(body + SOF_COMPONENTS_AT)
        return f.copy(
            sof = "SOF${seg.marker - JpegSegment.SOF0}",
            progressive = seg.marker in PROGRESSIVE_SOFS,
            height = b.u16(body + SOF_PRECISION_BYTES),
            width = b.u16(body + SOF_PRECISION_BYTES + 2),
            components = components,
            color =
            when (components) {
                1 -> ColorKind.GRAY
                3 -> ColorKind.RGB
                4 -> ColorKind.CMYK
                else -> ColorKind.UNKNOWN
            },
        )
    }

    private const val EXIF_ID = "Exif\u0000\u0000"
    private const val XMP_PREFIX = "http://ns.adobe.com/xap"
    private const val ICC_ID = "ICC_PROFILE"
    private const val ADOBE_ID = "Adobe"
}
