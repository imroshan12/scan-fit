public struct ReviewChecks: Sendable, Equatable {
    public let size: Bool
    public let dimensions: Bool
    public let jpeg: Bool

    public init(size: Bool, dimensions: Bool, jpeg: Bool) {
        self.size = size
        self.dimensions = dimensions
        self.jpeg = jpeg
    }

    public static let unchecked = ReviewChecks(size: false, dimensions: false, jpeg: false)

    public static func of(_ evaluation: SlotEvaluation) -> ReviewChecks {
        guard evaluation.verdict != .unknown else { return .unchecked }
        return ReviewChecks(
            size: !evaluation.failed.contains(.sizeKb),
            dimensions: !evaluation.failed.contains(.dims),
            jpeg: !evaluation.failed.contains(.format) && !evaluation.failed.contains(.encoding)
        )
    }
}
