import Foundation

// Mirrors spec/schema/exam.schema.json (schema v2). Exams are data: never hard-code a rule in app code
// (CLAUDE.md rule 5). If the schema cannot express a rule, extend the schema and the validator first.

public enum ExamCategory: String, Codable, Sendable, CaseIterable {
    case banking, insurance, regulator, ssc, upsc, railway, defence, teaching
    case statePsc = "state_psc"
    case entrance
}

public enum ExamStatus: String, Codable, Sendable {
    case active, hidden, retired
}

public enum Confidence: String, Codable, Sendable, CaseIterable {
    case high, medium, low
}

public enum SourceKind: String, Codable, Sendable {
    case official, secondary
}

public enum DocType: String, Codable, Sendable, CaseIterable {
    case photo
    case postcardPhoto = "postcard_photo"
    case signature
    case tripleSignature = "triple_signature"
    case leftThumb = "left_thumb"
    case thumbImpression = "thumb_impression"
    case leftHandFingersThumb = "left_hand_fingers_thumb"
    case rightHandFingersThumb = "right_hand_fingers_thumb"
    case handwrittenDeclaration = "handwritten_declaration"
    case photoId = "photo_id"
    case idProof = "id_proof"
    case class10Certificate = "class10_certificate"
    case categoryCertificate = "category_certificate"
    case pwdCertificate = "pwd_certificate"
    case scStCertificate = "sc_st_certificate"
}

public enum FileFormat: String, Codable, Sendable {
    case jpg, jpeg, png, pdf
}

public enum DimensionMode: String, Codable, Sendable {
    /// Must match exactly.
    case exact
    /// Aim for it; the portal tolerates others.
    case preferred
    /// A min/max box (and optional aspect range).
    case range
    /// Unspecified.
    case none
}

public struct SizeKB: Codable, Sendable, Equatable {
    public let min: Double?
    public let max: Double?
    public let target: Double?
}

public struct AspectRange: Codable, Sendable, Equatable {
    public let min: Double?
    public let max: Double?
}

public struct Dimensions: Codable, Sendable, Equatable {
    public let mode: DimensionMode
    public let width: Int?
    public let height: Int?
    public let minW: Int?
    public let minH: Int?
    public let maxW: Int?
    public let maxH: Int?
    public let aspectWOverH: AspectRange?
}

public struct PhysicalSize: Codable, Sendable, Equatable {
    public let w: Double?
    public let h: Double?
}

public struct NameDateStrip: Codable, Sendable, Equatable {
    public let required: Bool?
    public let verified: Bool?
    public let note: String?
}

public struct DocSpec: Codable, Sendable, Equatable, Identifiable {
    public let type: DocType
    public let required: Bool
    public let formats: [FileFormat]
    public let sizeKb: SizeKB
    public let dimensions: Dimensions
    public let dpi: Int?
    public let physicalCm: PhysicalSize?
    /// Base name without extension, if the portal expects one (NTA: "Photograph").
    public let filename: String?
    public let nameDateStrip: NameDateStrip?
    /// Verbatim text from the official notice. `nil` = not yet transcribed (UI must say "copy from notice").
    public let declarationText: String?
    public let notes: String?
    public let rules: [String]?

    public var id: DocType { type }
}

public struct Source: Codable, Sendable, Equatable {
    public let url: URL
    public let kind: SourceKind
}

public struct Exam: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let name: String
    /// Other spellings and Hindi forms, used only by search (ALGORITHMS §10).
    public let aliases: [String]?
    public let body: String
    public let category: ExamCategory
    public let portal: String?
    /// `true` = the portal captures a live photo; `nil` = not stated in sources.
    public let livePhotoCapture: Bool?
    public let status: ExamStatus
    public let documents: [DocSpec]
    public let specialRules: [String]
    public let sources: [Source]
    public let confidence: Confidence
    public let confidenceNote: String
    /// ISO date (yyyy-MM-dd).
    public let lastVerified: String

    /// Low-confidence presets must show the "Unverified" badge (CLAUDE.md rule 6).
    public var isUnverified: Bool { confidence == .low }
}
