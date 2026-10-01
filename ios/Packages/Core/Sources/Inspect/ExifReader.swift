import Foundation

/// Reads just the orientation tag and whether a GPS IFD exists. Tolerates truncated or hostile EXIF.
enum ExifReader {
    struct Exif {
        var orientation: Int?
        var hasGps: Bool
    }

    private static let maxEntries = 512

    static func read(_ b: [UInt8], bodyStart: Int, end: Int) -> Exif {
        let tiff = bodyStart + 6 // after "Exif\0\0"
        guard tiff + 8 <= end else { return Exif(orientation: nil, hasGps: false) }
        let order = b.ascii(tiff, 2)
        let little = order == "II"
        guard little || order == "MM" else { return Exif(orientation: nil, hasGps: false) }

        func r16(_ o: Int) -> Int { little ? b.u8(o) | (b.u8(o + 1) << 8) : b.u16(o) }
        func r32(_ o: Int) -> Int { little ? r16(o) | (r16(o + 2) << 16) : b.u32be(o) }

        let ifd0 = tiff + r32(tiff + 4)
        guard ifd0 >= tiff, ifd0 + 2 <= end else { return Exif(orientation: nil, hasGps: false) }
        var orientation: Int?
        var gps = false
        for index in 0..<min(r16(ifd0), maxEntries) {
            let entry = ifd0 + 2 + index * 12
            if entry + 12 > end { break }
            switch r16(entry) {
            case 0x0112:
                let value = r16(entry + 8)
                orientation = (1...8).contains(value) ? value : nil
            case 0x8825:
                gps = true
            default:
                break
            }
        }
        return Exif(orientation: orientation, hasGps: gps)
    }
}
