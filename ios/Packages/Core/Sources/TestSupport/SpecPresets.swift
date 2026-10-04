import Foundation
import ScanModel

/// The real presets, read from spec/ sources (exams, categories.json, popular.json), for tests that need a bundle.
/// spec/dist is not used: it may be stale or signed with the production key.
public enum SpecPresets {
    public enum Failure: Error { case badJSON(String) }

    public static func bundle(file: String = #filePath) throws -> PresetBundle {
        let exams = try SpecFiles.presetFiles(file: file)
            .sorted { $0.path < $1.path }
            .map { try PresetBundle.decodeExam(Data(contentsOf: $0)) }
        guard let categoriesJSON = try JSONSerialization.jsonObject(
            with: SpecFiles.data("presets/categories.json", file: file)
        ) as? [String: Any] else { throw Failure.badJSON("categories.json") }
        var categories: [String: PresetBundle.CategoryInfo] = [:]
        for (key, value) in categoriesJSON where !key.hasPrefix("_") {
            guard let aliases = (value as? [String: Any])?["aliases"] as? [String] else { throw Failure.badJSON(key) }
            categories[key] = PresetBundle.CategoryInfo(aliases: aliases)
        }
        guard let popularJSON = try JSONSerialization.jsonObject(
            with: SpecFiles.data("presets/popular.json", file: file)
        ) as? [String: Any], let popular = popularJSON["popular"] as? [String] else {
            throw Failure.badJSON("popular.json")
        }
        return PresetBundle(presetsVersion: 2, exams: exams, categories: categories, popular: popular)
    }
}
