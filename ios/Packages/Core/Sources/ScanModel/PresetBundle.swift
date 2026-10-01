import Foundation

/// The signed bundle published as `presets.json` (see spec/tools/build_presets.py).
public struct PresetBundle: Codable, Sendable, Equatable {
    public let schemaVersion: Int
    public let presetsVersion: Int
    public let generatedOn: String
    public let disclaimer: String
    public let exams: [Exam]

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
