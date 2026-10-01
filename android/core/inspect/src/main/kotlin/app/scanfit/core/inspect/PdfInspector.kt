package app.scanfit.core.inspect

internal object PdfInspector {
    private val PAGE = Regex("/Type\\s*/Page(?![s\\w])")
    private val ENCRYPT = Regex("/Encrypt\\b")

    fun inspect(b: ByteArray): InspectedFile {
        // Latin-1 keeps every byte as one char, so binary streams never throw and offsets are stable.
        val text = String(b, Charsets.ISO_8859_1)
        return InspectedFile(
            format = DetectedFormat.PDF,
            bytes = b.size,
            pdfPages = PAGE.findAll(text).count(),
            pdfEncrypted = ENCRYPT.containsMatchIn(text),
            pdfImageOnly = !text.contains("/Font"),
        )
    }
}
