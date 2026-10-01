import Foundation

/// Locates the repo's `spec/` directory from a test (the apps share data through it, never code).
public enum SpecFiles {
    public enum Failure: Error, CustomStringConvertible {
        case specNotFound(String)
        public var description: String {
            switch self {
            case let .specNotFound(from): "Could not find spec/ above \(from)"
            }
        }
    }

    /// Walks up from this source file until a directory containing `spec/ALGORITHMS.md` is found.
    public static func specDirectory(from file: String = #filePath) throws -> URL {
        var dir = URL(filePath: file).deletingLastPathComponent()
        for _ in 0..<12 {
            let candidate = dir.appending(path: "spec", directoryHint: .isDirectory)
            if FileManager.default.fileExists(atPath: candidate.appending(path: "ALGORITHMS.md").path) {
                return candidate
            }
            dir = dir.deletingLastPathComponent()
        }
        throw Failure.specNotFound(file)
    }

    public static func url(_ relativePath: String, file: String = #filePath) throws -> URL {
        try specDirectory(from: file).appending(path: relativePath)
    }

    public static func data(_ relativePath: String, file: String = #filePath) throws -> Data {
        try Data(contentsOf: url(relativePath, file: file))
    }

    public static func text(_ relativePath: String, file: String = #filePath) throws -> String {
        try String(contentsOf: url(relativePath, file: file), encoding: .utf8)
    }

    /// Every preset file under spec/presets/exams, sorted by path.
    public static func presetFiles(file: String = #filePath) throws -> [URL] {
        let root = try url("presets/exams", file: file)
        guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else {
            throw Failure.specNotFound(root.path)
        }
        var out: [URL] = []
        for case let item as URL in walker where item.pathExtension == "json" {
            out.append(item)
        }
        return out.sorted { $0.path < $1.path }
    }
}
