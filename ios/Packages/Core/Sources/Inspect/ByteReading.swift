import Foundation

/// Bounds-safe reads. Swift traps on out-of-range indexing, and the inspector must survive hostile files,
/// so every read outside the array yields 0 / "" instead of crashing.
public extension Array where Element == UInt8 {
    func u8(_ index: Int) -> Int {
        index >= 0 && index < count ? Int(self[index]) : 0
    }

    func u16(_ index: Int) -> Int { (u8(index) << 8) | u8(index + 1) }

    func u32be(_ index: Int) -> Int { (u16(index) << 16) | u16(index + 2) }

    func ascii(_ from: Int, _ length: Int) -> String {
        guard from >= 0, length >= 0, from + length <= count else { return "" }
        // `decoding:` is total: invalid bytes become U+FFFD (like the JVM), never nil,
        // so callers just compare the result.
        // swiftlint:disable:next optional_data_string_conversion
        return String(decoding: self[from..<(from + length)], as: UTF8.self)
    }

    func hasPrefix(_ values: [Int], at offset: Int = 0) -> Bool {
        guard offset >= 0, offset + values.count <= count else { return false }
        return values.indices.allSatisfy { u8(offset + $0) == values[$0] }
    }
}
