import Foundation

/// Why a preset bundle was refused. Raw values match the `sync_failure_reason` analytics enum and the
/// `expect` field of `spec/fixtures/presets_load/cases.json`.
public enum PresetLoadError: String, Error, Sendable, Equatable {
    case badSchema = "bad_schema"
    case staleVersion = "stale_version"
    case parse
}
