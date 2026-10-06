import Foundation
import Imaging
import Match
import ScanModel

/// The exam slot the photo is for. `unverified` = low-confidence preset: never "meets the rules" (CLAUDE.md rule 6).
public struct PhotoSlot: Sendable, Equatable {
    public let examId: String
    public let examName: String
    public let unverified: Bool
    public let spec: DocSpec

    /// The aspect the crop is locked to (ALGORITHMS 9.6); a free-ratio slot keeps the photo's own shape.
    public var aspect: Double? { Geometry.targetAspect(spec) }

    /// `T` of ALGORITHMS 9.4 in whole KB, for "Fitting to 38 KB"; nil when the slot has no maximum.
    public var targetKb: Int? {
        guard let max = spec.sizeKb.max else { return nil }
        let target = spec.sizeKb.target ?? ((spec.sizeKb.min ?? 0) + max) / 2
        return Int((target + 0.5).rounded(.down))
    }
}

public enum SourceProblem: Sendable, Equatable { case openFailed, noFace, failed }

public enum CropProblem: Sendable, Equatable { case noFace, severalFaces, failed }

public struct CropState: Sendable, Equatable {
    public let slot: PhotoSlot
    public let image: Raster
    public var rect: CropRect
    /// The face is too large for the target coverage (ALGORITHMS 9.6 `coverage_adjusted`).
    public var tight: Bool
    public var problem: CropProblem?
    /// The final face check on the crop is running.
    public var checking = false
}

public struct PhotoOptions: Sendable, Equatable {
    public var whiteBackground = false
    /// false once the segmenter said it cannot run on this device.
    public var whiteBackgroundAvailable = true
    public var nameDate = false
    public var name = ""
    public var date = ""
}

public struct ReviewReady: Sendable, Equatable {
    /// The fitted JPEG, exactly as it would be written.
    public let bytes: [UInt8]
    public let kb: Int
    public let width: Int
    public let height: Int
    /// EXACT or ACCEPTED for this slot (ALGORITHMS 9.7), checked on the re-inspected bytes.
    public let meetsRules: Bool
    /// Which other exams accept these bytes (ALGORITHMS 4 "Match note on review").
    public let note: MatchNote
    public var checks: ReviewChecks = .unchecked
}

public enum ReviewResult: Sendable, Equatable {
    case working(targetKb: Int?)
    case ready(ReviewReady)
    case failed(FitError)
}

public struct ReviewState: Sendable, Equatable {
    public let slot: PhotoSlot
    public var options: PhotoOptions
    public var result: ReviewResult
    public var before: Raster?
    public var restored = false
    public var retainFailed = false
}

/// The photo flow's steps (UI_UX §3 Capture/crop, Review). Same states as Android's `PhotoUiState`.
public enum PhotoState: Sendable, Equatable {
    case loading
    /// The exam or its photo slot is not in the trusted presets.
    case notFound
    case pickSource(PhotoSlot, SourceProblem?)
    case findingFace(PhotoSlot)
    case crop(CropState)
    case review(ReviewState)

    var slot: PhotoSlot? {
        switch self {
        case .loading, .notFound: nil
        case let .pickSource(slot, _), let .findingFace(slot): slot
        case let .crop(crop): crop.slot
        case let .review(review): review.slot
        }
    }
}
