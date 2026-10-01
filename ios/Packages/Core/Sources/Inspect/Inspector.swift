import Foundation

/// Reads a file's facts from its bytes (ALGORITHMS section 5 / 9.2). The format comes from magic bytes, never the
/// extension. Malformed input never traps.
public enum Inspector {
    private static let heicBrands: Set<String> = ["heic", "heix", "mif1", "heim", "heis"]
    private static let extensionFormats: [String: DetectedFormat] = [
        "jpg": .jpeg, "jpeg": .jpeg, "png": .png, "pdf": .pdf, "heic": .heic, "heif": .heic, "webp": .webp,
    ]

    public static func detectFormat(_ b: [UInt8]) -> DetectedFormat {
        if b.hasPrefix([0xFF, 0xD8, 0xFF]) { return .jpeg }
        if b.hasPrefix([0x89, 0x50, 0x4E, 0x47]) { return .png }
        if b.ascii(0, 5) == "%PDF-" { return .pdf }
        if b.count >= 12, b.ascii(4, 4) == "ftyp", heicBrands.contains(b.ascii(8, 4)) { return .heic }
        if b.count >= 12, b.ascii(0, 4) == "RIFF", b.ascii(8, 4) == "WEBP" { return .webp }
        return .unknown
    }

    public static func inspect(_ data: Data, fileName: String = "") -> InspectedFile {
        inspect([UInt8](data), fileName: fileName)
    }

    public static func inspect(_ bytes: [UInt8], fileName: String = "") -> InspectedFile {
        var file: InspectedFile
        switch detectFormat(bytes) {
        case .jpeg: file = JpegInspector.inspect(bytes)
        case .png: file = inspectPng(bytes)
        case .pdf: file = PdfInspector.inspect(bytes)
        case let other: file = InspectedFile(format: other, bytes: bytes.count)
        }
        file.issues = contextFreeIssues(file, fileName: fileName)
        return file
    }

    private static func contextFreeIssues(_ f: InspectedFile, fileName: String) -> [Issue] {
        var issues: [Issue] = []
        let ext = fileName.split(separator: ".", omittingEmptySubsequences: false)
            .dropFirst().last.map { $0.lowercased() } ?? ""
        if let named = extensionFormats[ext], named != f.format { issues.append(.extensionMismatch) }
        if f.format == .jpeg {
            if f.color == .cmyk { issues.append(.cmykColor) }
            if f.progressive { issues.append(.progressiveJpeg) }
            if f.hasGps { issues.append(.hasGpsExif) }
            if let orientation = f.exifOrientation, orientation != 1 { issues.append(.rotatedByExif) }
        }
        if f.format == .heic { issues.append(.heicNotAccepted) }
        if f.format == .pdf, f.pdfEncrypted { issues.append(.pdfEncrypted) }
        return issues
    }

    private static func inspectPng(_ b: [UInt8]) -> InspectedFile {
        var file = InspectedFile(format: .png, bytes: b.count)
        guard b.count >= 26 else { return file }
        file.width = b.u32be(16)
        file.height = b.u32be(20)
        switch b.u8(25) {
        case 0, 4: file.color = .gray
        case 2, 3, 6: file.color = .rgb
        default: file.color = .unknown
        }
        return file
    }
}

enum JpegInspector {
    private static let progressiveSofs: Set<Int> = [0xC2, 0xC6, 0xCA, 0xCE]

    static func inspect(_ b: [UInt8]) -> InspectedFile {
        var file = InspectedFile(format: .jpeg, bytes: b.count)
        for seg in JpegWalk(b).segments {
            let body = seg.bodyStart
            if seg.marker == JpegSegment.app0, b.ascii(body, 5) == "JFIF\0" {
                file.jfif = JfifDensity(units: b.u8(body + 7), xDensity: b.u16(body + 8), yDensity: b.u16(body + 10))
            } else if seg.marker == JpegSegment.app1, b.ascii(body, 6) == "Exif\0\0" {
                let exif = ExifReader.read(b, bodyStart: body, end: seg.end)
                file.hasExif = true
                file.exifOrientation = exif.orientation
                file.hasGps = exif.hasGps
            } else if seg.marker == JpegSegment.app1, b.ascii(body, 23) == "http://ns.adobe.com/xap" {
                file.hasXmp = true
            } else if seg.marker == JpegSegment.app2, b.ascii(body, 11) == "ICC_PROFILE" {
                file.hasIcc = true
            } else if seg.marker == JpegSegment.app14, b.ascii(body, 5) == "Adobe" {
                file.hasAdobe = true
            } else if seg.isSof, body + 5 < b.count {
                let components = b.u8(body + 5)
                file.sof = "SOF\(seg.marker - JpegSegment.sof0)"
                file.progressive = progressiveSofs.contains(seg.marker)
                file.height = b.u16(body + 1)
                file.width = b.u16(body + 3)
                file.components = components
                switch components {
                case 1: file.color = .gray
                case 3: file.color = .rgb
                case 4: file.color = .cmyk
                default: file.color = .unknown
                }
            }
        }
        return file
    }
}

enum PdfInspector {
    static func inspect(_ b: [UInt8]) -> InspectedFile {
        // Latin-1 keeps every byte as one character, so binary streams never break the search.
        let text = String(decoding: b.map { UInt16($0) }, as: UTF16.self)
        var file = InspectedFile(format: .pdf, bytes: b.count)
        file.pdfPages = pageCount(in: text)
        file.pdfEncrypted = text.range(of: #"/Encrypt\b"#, options: .regularExpression) != nil
        file.pdfImageOnly = !text.contains("/Font")
        return file
    }

    private static func pageCount(in text: String) -> Int {
        var count = 0
        var searchRange = text.startIndex..<text.endIndex
        while let match = text.range(of: #"/Type\s*/Page(?![s\w])"#, options: .regularExpression, range: searchRange) {
            count += 1
            searchRange = match.upperBound..<text.endIndex
        }
        return count
    }
}
