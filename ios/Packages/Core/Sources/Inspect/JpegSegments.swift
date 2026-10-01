import Foundation

/// One marker segment of a JPEG: `start..<end` covers the marker, the length field and the body.
public struct JpegSegment: Sendable, Equatable {
    public let marker: Int
    public let start: Int
    public let end: Int

    public static let soi = 0xD8
    public static let eoi = 0xD9
    public static let sos = 0xDA
    public static let app0 = 0xE0
    public static let app1 = 0xE1
    public static let app2 = 0xE2
    public static let app13 = 0xED
    public static let app14 = 0xEE
    public static let app15 = 0xEF
    public static let com = 0xFE
    public static let sof0 = 0xC0

    /// First byte after the 2-byte length field.
    public var bodyStart: Int { start + 4 }
    public var isApp: Bool { (Self.app0...Self.app15).contains(marker) }
    /// SOF0...SOF15, excluding DHT (C4), JPG (C8) and DAC (CC), which share the range.
    public var isSof: Bool { (0xC0...0xCF).contains(marker) && ![0xC4, 0xC8, 0xCC].contains(marker) }
}

/// Header segments of a JPEG up to and including SOS. Never traps: a truncated or corrupt file simply ends the walk.
/// `scanStart` is the offset after the SOS segment (entropy-coded data), or `nil` when no SOS was reached.
public struct JpegWalk: Sendable {
    public let segments: [JpegSegment]
    public let scanStart: Int?

    public var reachedScan: Bool { scanStart != nil }

    public init(_ bytes: [UInt8]) {
        var found: [JpegSegment] = []
        var offset = 2
        while offset + 1 < bytes.count {
            guard bytes.u8(offset) == 0xFF else {
                offset += 1
                continue
            }
            let marker = bytes.u8(offset + 1)
            if marker == 0xFF {
                offset += 1
            } else if marker == JpegSegment.soi || marker == 0x01 || marker == 0x00 || (0xD0...0xD7).contains(marker) {
                offset += 2
            } else if marker == JpegSegment.eoi || offset + 3 >= bytes.count {
                break
            } else {
                let length = bytes.u16(offset + 2)
                let end = offset + 2 + length
                if length < 2 || end > bytes.count { break }
                found.append(JpegSegment(marker: marker, start: offset, end: end))
                if marker == JpegSegment.sos {
                    segments = found
                    scanStart = end
                    return
                }
                offset = end
            }
        }
        segments = found
        scanStart = nil
    }
}
