import Foundation

/// A PDF-shaped byte array for data and view-model tests: `pages` page dictionaries with an image XObject each (so the
/// Inspector sees an image-only PDF), padded with a stream of spaces to exactly `size` bytes. Not renderable.
public enum TestPdf {
    public static func make(pages: Int, size: Int, font: Bool = false, encrypted: Bool = false) -> [UInt8] {
        var body = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        body += "2 0 obj\n<< /Type /Pages /Count \(pages) >>\nendobj\n"
        for i in 0..<pages {
            let resources = font ? "/Font << /F1 9 0 R >>" : "/XObject << /Im1 9 0 R >>"
            body += "\(i + 3) 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << \(resources) >> >>\nendobj\n"
        }
        if encrypted { body += "trailer\n<< /Encrypt 8 0 R >>\n" }
        body += "99 0 obj\n<< /Length 0 >>\nstream\n"
        let tail = "\nendstream\nendobj\n%%EOF\n"
        let padding = size - body.utf8.count - tail.utf8.count
        precondition(padding >= 0, "size \(size) is too small for \(pages) pages")
        return Array((body + String(repeating: " ", count: padding) + tail).utf8)
    }
}
