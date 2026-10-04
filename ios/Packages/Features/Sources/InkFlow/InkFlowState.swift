import Foundation
import Imaging
import Match
import ScanModel

/// The exam slot the ink document is for (ALGORITHMS 9.5: its cleanup variant and match kind come from its type).
public struct InkSlot: Sendable, Equatable {
    public let examId: String
    public let examName: String
    public let unverified: Bool
    public let spec: DocSpec

    public var kind: DocKind { DocKind.of(spec.type) }

    public var variant: InkVariant {
        switch kind {
        case .signature: .signature
        case .thumb, .fingers: .thumb
        default: .document
        }
    }

    public var pipeline: Pipeline {
        switch variant {
        case .signature: .signatureCleanup
        case .thumb: .thumbCleanup
        case .document: .documentCleanup
        }
    }

    /// Signatures need the one-time "running handwriting" confirmation before saving (§9.5).
    public var needsHandwritingConfirmation: Bool { kind == .signature }

    /// "Crisp black" and "Darker ink" apply to binarised ink only; a thumb keeps its ridges (§9.5).
    public var hasInkOptions: Bool { variant != .thumb }

    /// `T` of ALGORITHMS 9.4 in whole KB, for progress text; nil when the slot has no maximum.
    public var targetKb: Int? {
        guard let max = spec.sizeKb.max else { return nil }
        let target = spec.sizeKb.target ?? ((spec.sizeKb.min ?? 0) + max) / 2
        return Int((target + 0.5).rounded(.down))
    }
}

public struct InkCropState: Sendable, Equatable {
    public let slot: InkSlot
    public let image: Raster
    public var rect: CropRect
}

/// The review options of §9.5: crisp black (signature on, document off) and the "Darker ink" factor.
public struct InkReviewOptions: Sendable, Equatable {
    public var crispBlack: Bool
    public var inkFactor = InkOptions.defaultInkFactor
}

public struct InkReady: Sendable, Equatable {
    /// The fitted JPEG, exactly as it would be written.
    public let bytes: [UInt8]
    public let kb: Int
    public let width: Int
    public let height: Int
    /// EXACT or ACCEPTED for this slot (ALGORITHMS 9.7), checked on the re-inspected bytes as the slot's kind.
    public let meetsRules: Bool
    /// The coverage gate (§3 step 8): a warning only, saving is still allowed.
    public let quality: InkQuality
}

public enum InkReviewResult: Sendable, Equatable {
    case working(targetKb: Int?)
    case ready(InkReady)
    case failed(FitError)
}

public struct InkReviewState: Sendable, Equatable {
    public let slot: InkSlot
    public var options: InkReviewOptions
    public var result: InkReviewResult
    /// The handwriting tick (signatures only): from the device, or ticked now.
    public var handwritingConfirmed: Bool
}

/// The ink flow's steps (UI_UX §3 Capture/crop, Review). Same states as Android's `InkUiState`.
public enum InkState: Sendable, Equatable {
    case loading
    /// The exam or this ink slot is not in the trusted presets.
    case notFound
    case pickSource(InkSlot, openFailed: Bool)
    case opening(InkSlot)
    case crop(InkCropState)
    case review(InkReviewState)

    var slot: InkSlot? {
        switch self {
        case .loading, .notFound: nil
        case let .pickSource(slot, _), let .opening(slot): slot
        case let .crop(crop): crop.slot
        case let .review(review): review.slot
        }
    }
}
