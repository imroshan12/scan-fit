/// What Save to My Kit shows on a review (ALGORITHMS 13.1). In ScanModel so the design system can draw it.
public enum KitSaveStatus: Sendable, Equatable, CaseIterable {
    case idle, saving, saved, full, failed
}
