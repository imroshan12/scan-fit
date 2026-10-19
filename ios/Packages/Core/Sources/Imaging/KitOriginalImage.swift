/// The picture My Kit keeps for reuse (ALGORITHMS 13.1): the flow's upright working source, at most 2400 px on the long
/// side, as a baseline JPEG at quality 92 with metadata stripped by the patcher. Pure: the encoder is passed in.
public enum KitOriginalImage {
    public static let maxLongSide = 2400
    public static let quality = 92

    /// The stored size for a `width` × `height` source (`kit_size_cases`).
    public static func storedSize(width: Int, height: Int) -> (width: Int, height: Int) {
        if max(width, height) <= maxLongSide { return (width, height) }
        if width >= height {
            return (maxLongSide, max(1, roundHalfUp(Double(height) * Double(maxLongSide) / Double(width))))
        }
        return (max(1, roundHalfUp(Double(width) * Double(maxLongSide) / Double(height))), maxLongSide)
    }

    /// The JPEG to keep, or nil when encoding or patching failed.
    public static func encode(_ raster: Raster, encoder: any JpegEncoder) -> [UInt8]? {
        let size = storedSize(width: raster.width, height: raster.height)
        let sized = size == (raster.width, raster.height)
            ? raster
            : Resampler.resize(raster, width: size.width, height: size.height)
        return try? JpegPatcher.patch(encoder.encode(sized, quality: quality)).get()
    }
}
