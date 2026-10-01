import Foundation
import Inspect

public enum PatchError: String, Error, Sendable, Equatable {
    case notJpeg = "NOT_JPEG"
    case truncated = "TRUNCATED"
    case notBaseline = "NOT_BASELINE"
    case cmyk = "CMYK"
    case grayscaleNotAllowed = "GRAYSCALE_NOT_ALLOWED"
    case nonSrgbProfile = "NON_SRGB_PROFILE"
}

/// Byte post-processing for every exported JPEG (ALGORITHMS 1.5 / 9.3): strip metadata, force a JFIF header with the
/// slot's DPI, assert baseline + RGB, and pad to a minimum size with COM segments. Pure bytes.
public enum JpegPatcher {
    public static let defaultDpi = 200
    private static let minCom = 4
    private static let maxCom = 65_537 // 2 marker + 2 length + 65 533 payload
    private static let padByte: UInt8 = 0x20

    public static func patch(
        _ bytes: [UInt8],
        dpi: Int = defaultDpi,
        allowGrayscale: Bool = false
    ) -> Result<[UInt8], PatchError> {
        guard Inspector.detectFormat(bytes) == .jpeg else { return .failure(.notJpeg) }
        let walk = JpegWalk(bytes)
        guard let scanStart = walk.scanStart else { return .failure(.truncated) }
        let info = Inspector.inspect(bytes)
        guard let sof = info.sof else { return .failure(.truncated) }
        if sof != "SOF0" { return .failure(.notBaseline) }
        switch info.components {
        case 4: return .failure(.cmyk)
        case 1: if !allowGrayscale { return .failure(.grayscaleNotAllowed) }
        case 3: break
        default: return .failure(.notBaseline)
        }
        if let icc = iccProfile(bytes, walk), !isSrgb(icc) { return .failure(.nonSrgbProfile) }

        var out: [UInt8] = [0xFF, UInt8(JpegSegment.soi)]
        out.reserveCapacity(bytes.count)
        out += jfif(dpi: dpi)
        for seg in walk.segments where keep(seg) { out += bytes[seg.start..<seg.end] }
        out += bytes[scanStart...]
        return .success(out)
    }

    /// Inserts COM segments right after the JFIF header until the file is exactly `targetBytes`
    /// (overshoot of at most 3 only when the gap starts below 4).
    public static func pad(_ bytes: [UInt8], to targetBytes: Int) -> [UInt8] {
        var gap = targetBytes - bytes.count
        if gap <= 0 { return bytes }
        let at = insertionPoint(bytes)
        var out = Array(bytes[0..<at])
        out.reserveCapacity(targetBytes + minCom)
        while gap > 0 {
            var length = min(gap, maxCom)
            if gap < minCom { length = minCom }
            let remainder = gap - length
            if (1...3).contains(remainder) { length -= minCom - remainder }
            out += com(totalLength: length)
            gap -= length
        }
        out += bytes[at...]
        return out
    }

    private static func keep(_ seg: JpegSegment) -> Bool {
        if seg.marker == JpegSegment.app14 { return true }
        if seg.marker == JpegSegment.app2 { return false } // only reached for sRGB (others failed above)
        if seg.isApp { return false } // APP0 (rewritten), APP1 EXIF/XMP, APP13 IPTC and every other APPn
        return seg.marker != JpegSegment.com
    }

    private static func jfif(dpi: Int) -> [UInt8] {
        [0xFF, UInt8(JpegSegment.app0), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01,
         UInt8((dpi >> 8) & 0xFF), UInt8(dpi & 0xFF), UInt8((dpi >> 8) & 0xFF), UInt8(dpi & 0xFF), 0x00, 0x00]
    }

    private static func com(totalLength: Int) -> [UInt8] {
        var out = [UInt8](repeating: padByte, count: totalLength)
        out[0] = 0xFF
        out[1] = UInt8(JpegSegment.com)
        let length = totalLength - 2
        out[2] = UInt8((length >> 8) & 0xFF)
        out[3] = UInt8(length & 0xFF)
        return out
    }

    private static func insertionPoint(_ bytes: [UInt8]) -> Int {
        let jfif = JpegWalk(bytes).segments.first {
            $0.marker == JpegSegment.app0 && bytes.ascii($0.bodyStart, 5) == "JFIF\0"
        }
        return jfif?.end ?? 2
    }

    /// Concatenated ICC data from all APP2 chunks (Latin-1), or nil when there is no ICC profile.
    private static func iccProfile(_ b: [UInt8], _ walk: JpegWalk) -> [UInt8]? {
        let chunks = walk.segments.filter {
            $0.marker == JpegSegment.app2 && b.ascii($0.bodyStart, 11) == "ICC_PROFILE"
        }
        guard !chunks.isEmpty else { return nil }
        var data: [UInt8] = []
        for chunk in chunks {
            let start = chunk.bodyStart + 14
            if start < chunk.end { data += b[start..<chunk.end] }
        }
        return data
    }

    /// ICC v2 stores descriptions as ASCII, v4 (`mluc`) as UTF-16BE: accept `sRGB` in either form.
    private static func isSrgb(_ profile: [UInt8]) -> Bool {
        contains(profile, [0x73, 0x52, 0x47, 0x42])
            || contains(profile, [0x00, 0x73, 0x00, 0x52, 0x00, 0x47, 0x00, 0x42])
    }

    private static func contains(_ haystack: [UInt8], _ needle: [UInt8]) -> Bool {
        guard haystack.count >= needle.count else { return false }
        for start in 0...(haystack.count - needle.count)
        where Array(haystack[start..<(start + needle.count)]) == needle {
            return true
        }
        return false
    }
}
