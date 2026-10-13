import SwiftUI

/// The review's verdict for its own slot: icon + text, never colour alone (UI_UX §6). A low-confidence preset never
/// "meets the rules" (CLAUDE.md rule 6); a Custom size meets "your size" (ALGORITHMS 11).
public struct SlotVerdict: View {
    private let meetsRules: Bool
    private let examName: String
    private let unverified: Bool
    private let custom: Bool
    private let strings: Strings

    public init(meetsRules: Bool, examName: String, unverified: Bool, custom: Bool, strings: Strings) {
        self.meetsRules = meetsRules
        self.examName = examName
        self.unverified = unverified
        self.custom = custom
        self.strings = strings
    }

    public var body: some View {
        let (ok, text): (Bool, String) = if !meetsRules {
            (false, custom ? strings.customMisses : strings.photoMissesRules(exam: examName))
        } else if custom {
            (true, strings.customMeets)
        } else if unverified {
            (false, strings.matchLikelyOk)
        } else {
            (true, strings.photoMeetsRules(exam: examName))
        }
        Label {
            Text(text).scanFitText(.body)
        } icon: {
            Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .foregroundStyle(ok ? ScanFitColor.success : ScanFitColor.warning)
        }
    }
}
