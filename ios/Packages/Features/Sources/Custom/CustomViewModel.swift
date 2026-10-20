import Foundation
import Observation
import ScanData
import ScanModel

/// The Custom size form as typed: text fields, so a half-typed value never jumps (ALGORITHMS 11).
public struct CustomForm: Equatable, Sendable {
    public var doc: DocType = .photo
    public var unit: SizeUnit = .px
    public var width = ""
    public var height = ""
    public var minKb = ""
    public var maxKb = ""
    public var dpi = ""

    public init(
        doc: DocType = .photo, unit: SizeUnit = .px, width: String = "", height: String = "",
        minKb: String = "", maxKb: String = "", dpi: String = ""
    ) {
        self.doc = doc
        self.unit = unit
        self.width = width
        self.height = height
        self.minKb = minKb
        self.maxKb = maxKb
        self.dpi = dpi
    }

    public var fields: CustomSizeFields {
        CustomSizeFields(
            doc: doc, unit: unit, width: Double(width), height: Double(height),
            minKb: Int(minKb), maxKb: Int(maxKb), dpi: Int(dpi)
        )
    }
}

/// UI_UX §3 Custom size: what the file is and the form's limits, then the usual photo or ink flow. Same rules as
/// Android's `CustomViewModel`.
@MainActor
@Observable
public final class CustomViewModel {
    public var form = CustomForm()

    /// Continue was tapped with errors: show every code, including the ones for empty fields.
    public private(set) var attempted = false
    @ObservationIgnored private let preferences: UserPreferences?

    /// `doc` is what Home's card chose (ALGORITHMS 11); a kind Custom size does not make starts at Photo.
    public init(preferences: UserPreferences?, doc: DocType? = nil) {
        self.preferences = preferences
        if let doc, CustomSize.docs.contains(doc) { form.doc = doc }
    }

    public var errors: [CustomSizeError] { form.fields.errors }

    /// The size in pixels while entering centimetres, for the "= 276 × 354 px" line.
    public var pixels: (width: Int, height: Int)? {
        guard form.unit == .cm, let w = form.fields.widthPx, let h = form.fields.heightPx else { return nil }
        return (w, h)
    }

    public var recent: [CustomSize] { (preferences?.recentCustomSizes ?? []).compactMap(CustomSize.parse) }

    /// Whether the screen shows `error` now: once its field has text, or after Continue was tried.
    public func shows(_ error: CustomSizeError) -> Bool {
        guard errors.contains(error) else { return false }
        if attempted { return true }
        return switch error {
        case .maxKbMissing, .sizePair: false
        case .maxKbRange: !form.maxKb.isEmpty
        case .minKbRange: !form.minKb.isEmpty && !form.maxKb.isEmpty
        case .widthRange: !form.width.isEmpty
        case .heightRange: !form.height.isEmpty
        case .dpiRange: !form.dpi.isEmpty
        }
    }

    /// The form's number fields, so a text field can bind to one by value.
    public enum Field: Hashable, Sendable { case width, height, dpi, minKb, maxKb }

    public func text(_ field: Field) -> String {
        switch field {
        case .width: form.width
        case .height: form.height
        case .dpi: form.dpi
        case .minKb: form.minKb
        case .maxKb: form.maxKb
        }
    }

    /// Keeps only what the field can hold: digits, and for centimetres one decimal point with two decimals.
    public func update(_ field: Field, _ text: String) {
        switch field {
        case .width: form.width = Self.size(text, form.unit)
        case .height: form.height = Self.size(text, form.unit)
        case .dpi: form.dpi = Self.whole(text)
        case .minKb: form.minKb = Self.whole(text)
        case .maxKb: form.maxKb = Self.whole(text)
        }
    }

    public func setWidth(_ text: String) { update(.width, text) }
    public func setHeight(_ text: String) { update(.height, text) }
    public func setMinKb(_ text: String) { update(.minKb, text) }
    public func setMaxKb(_ text: String) { update(.maxKb, text) }
    public func setDpi(_ text: String) { update(.dpi, text) }

    /// Fills the form from a recent size (always in pixels: the canonical text keeps pixels only).
    public func useRecent(_ size: CustomSize) {
        attempted = false
        form = CustomForm(
            doc: size.doc, unit: .px,
            width: size.width.map(String.init) ?? "", height: size.height.map(String.init) ?? "",
            minKb: size.minKb > 0 ? String(size.minKb) : "", maxKb: String(size.maxKb), dpi: String(size.dpi)
        )
    }

    /// The size to make, remembered as recent; nil (and every error shown) while the form is not valid.
    public func continueTapped() -> CustomSize? {
        guard let size = form.fields.size else {
            attempted = true
            return nil
        }
        preferences?.rememberCustomSize(size.text)
        return size
    }

    private static let maxDigits = 5, maxDecimals = 2

    /// Digits only, at most 5 (the largest valid value is 5120).
    static func whole(_ text: String) -> String { String(text.filter(\.isASCIIDigit).prefix(maxDigits)) }

    /// Pixels: digits only. Centimetres: digits and one decimal point, two decimals at most.
    static func size(_ text: String, _ unit: SizeUnit) -> String {
        guard unit == .cm else { return whole(text) }
        let kept = text.filter { $0.isASCIIDigit || $0 == "." }
        guard let point = kept.firstIndex(of: ".") else { return String(kept.prefix(maxDigits)) }
        let decimals = kept[kept.index(after: point)...].filter(\.isASCIIDigit).prefix(maxDecimals)
        return String(kept[..<point].prefix(maxDigits)) + "." + decimals
    }
}

private extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}
