package app.scanfit.core.testing

/**
 * A PDF-shaped byte array for data and view-model tests: `pages` page dictionaries with an image XObject each (so the
 * Inspector sees an image-only PDF), padded with a stream of spaces to exactly [size] bytes. Not renderable.
 */
object TestPdf {
    fun make(
        pages: Int,
        size: Int,
        font: Boolean = false,
        encrypted: Boolean = false,
    ): ByteArray {
        val body =
            buildString {
                append("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
                append("2 0 obj\n<< /Type /Pages /Count $pages >>\nendobj\n")
                repeat(pages) {
                    val resources = if (font) "/Font << /F1 9 0 R >>" else "/XObject << /Im1 9 0 R >>"
                    append("${it + 3} 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << $resources >> >>\nendobj\n")
                }
                if (encrypted) append("trailer\n<< /Encrypt 8 0 R >>\n")
                append("99 0 obj\n<< /Length 0 >>\nstream\n")
            }
        val tail = "\nendstream\nendobj\n%%EOF\n"
        val padding = size - body.length - tail.length
        require(padding >= 0) { "size $size is too small for $pages pages" }
        return (body + " ".repeat(padding) + tail).toByteArray(Charsets.US_ASCII)
    }
}
