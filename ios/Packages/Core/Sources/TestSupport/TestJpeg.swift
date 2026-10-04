/// Valid fitted-file stand-ins for tests that inspect or verify bytes.
public enum TestJpeg {
    /// A structurally valid baseline JPEG (SOI, JFIF, SOF0 with 3 components, SOS, EOI) of exactly `size` bytes, so
    /// the real Inspector and match engine judge it like a fitted file. COM segments fill it up, as padding does.
    public static func make(width: Int, height: Int, size: Int) -> [UInt8] {
        var head: [UInt8] = [0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 1, 0, 200, 0, 200, 0, 0]
        head += [0xFF, 0xC0, 0, 17, 8, UInt8(height >> 8), UInt8(height & 0xFF), UInt8(width >> 8), UInt8(width & 0xFF)]
        head += [3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]
        let tail: [UInt8] = [0xFF, 0xDA, 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0, 0x55, 0xFF, 0xD9]
        var gap = size - head.count - tail.count
        precondition(gap == 0 || gap >= 4, "size \(size) cannot be padded exactly")
        while gap > 0 {
            var n = min(gap, 65537)
            if (1...3).contains(gap - n) { n -= 4 - (gap - n) }
            let payload = n - 4
            head += [0xFF, 0xFE, UInt8((payload + 2) >> 8), UInt8((payload + 2) & 0xFF)]
            head += [UInt8](repeating: 0x20, count: payload)
            gap -= n
        }
        return head + tail
    }
}
