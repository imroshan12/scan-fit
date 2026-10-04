/// A save's lifecycle (ALGORITHMS 1.6): idle → saving → saved, or a retryable error.
public enum ExportState: String, Sendable, Equatable {
    case idle, saving, saved
    case saveFailed = "save_failed"
    case verifyFailed = "verify_failed"
}
