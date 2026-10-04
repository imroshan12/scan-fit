import Foundation

/// The signed bundle published as `presets.json` (see spec/tools/build_presets.py).
public struct PresetBundle: Codable, Sendable, Equatable {
    public let schemaVersion: Int
    public let presetsVersion: Int
    public let generatedOn: String
    public let disclaimer: String
    public let exams: [Exam]
    /// Search words per category (ALGORITHMS §10). Keys are category names; note that `convertFromSnakeCase` rewrites
    /// dictionary keys too (`state_psc` arrives as `statePsc`), so look them up with `ExamSearch`, never by raw value.
    public let categories: [String: CategoryInfo]?
    /// Exam ids, most popular first (Home "Popular now", match-note and search ordering).
    public let popular: [String]?

    public struct CategoryInfo: Codable, Sendable, Equatable {
        public let aliases: [String]

        public init(aliases: [String]) {
            self.aliases = aliases
        }
    }

    /// The newest `schema_version` this build understands. A larger one needs an app update.
    public static let supportedSchemaVersion = 2

    /// The two header fields every loader reads first, before decoding exams.
    public struct Header: Codable, Sendable, Equatable {
        public let schemaVersion: Int
        public let presetsVersion: Int
    }

    public static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }

    public static func decodeHeader(_ data: Data) throws -> Header {
        try decoder().decode(Header.self, from: data)
    }

    public static func decode(_ data: Data) throws -> PresetBundle {
        try decoder().decode(PresetBundle.self, from: data)
    }

    /// Decodes a single preset file (`spec/presets/exams/<category>/<id>.json`).
    public static func decodeExam(_ data: Data) throws -> Exam {
        try decoder().decode(Exam.self, from: data)
    }
}

public extension PresetBundle {
    /// Builds a bundle in memory (tests, previews). The app always decodes the signed `presets.json`.
    init(
        schemaVersion: Int = PresetBundle.supportedSchemaVersion,
        presetsVersion: Int,
        exams: [Exam],
        categories: [String: CategoryInfo] = [:],
        popular: [String] = []
    ) {
        self.schemaVersion = schemaVersion
        self.presetsVersion = presetsVersion
        generatedOn = ""
        disclaimer = ""
        self.exams = exams
        self.categories = categories
        self.popular = popular
    }
}
