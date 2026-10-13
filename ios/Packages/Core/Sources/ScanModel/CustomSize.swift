import Foundation

/// Why a Custom size cannot be used yet (ALGORITHMS 11), in the order the codes are reported.
public enum CustomSizeError: String, Sendable, CaseIterable {
    case maxKbMissing = "max_kb_missing"
    case maxKbRange = "max_kb_range"
    case minKbRange = "min_kb_range"
    case sizePair = "size_pair"
    case widthRange = "width_range"
    case heightRange = "height_range"
    case dpiRange = "dpi_range"
}

public enum SizeUnit: String, Sendable, CaseIterable { case px, cm }

/// What the user typed on the Custom size screen; numbers are nil when the field is empty.
public struct CustomSizeFields: Sendable, Equatable {
    public var doc: DocType
    public var unit: SizeUnit
    public var width: Double?
    public var height: Double?
    public var minKb: Int?
    public var maxKb: Int?
    public var dpi: Int?

    public init(
        doc: DocType, unit: SizeUnit = .px, width: Double? = nil, height: Double? = nil,
        minKb: Int? = nil, maxKb: Int? = nil, dpi: Int? = nil
    ) {
        self.doc = doc
        self.unit = unit
        self.width = width
        self.height = height
        self.minKb = minKb
        self.maxKb = maxKb
        self.dpi = dpi
    }

    /// The size in pixels after the cm conversion, or nil when the field is empty.
    public var widthPx: Int? { width.map(toPx) }
    public var heightPx: Int? { height.map(toPx) }

    private func toPx(_ value: Double) -> Int {
        let pixels = unit == .cm ? value * Double(dpi ?? CustomSize.defaultDpi) / 2.54 : value
        return Int((pixels + 0.5).rounded(.down))
    }

    /// Every code that applies, in the spec's order; empty = valid.
    public var errors: [CustomSizeError] {
        var codes: [CustomSizeError] = []
        let w = widthPx, h = heightPx, dpi = dpi ?? CustomSize.defaultDpi
        if let maxKb {
            if !(1...CustomSize.maxKbLimit).contains(maxKb) { codes.append(.maxKbRange) }
        } else {
            codes.append(.maxKbMissing)
        }
        if let minKb, !(minKb >= 0 && (maxKb.map { minKb < $0 } ?? true)) { codes.append(.minKbRange) }
        if (w == nil) != (h == nil) { codes.append(.sizePair) }
        if let w, !CustomSize.pxRange.contains(w) { codes.append(.widthRange) }
        if let h, !CustomSize.pxRange.contains(h) { codes.append(.heightRange) }
        if !CustomSize.dpiRange.contains(dpi) { codes.append(.dpiRange) }
        return codes
    }

    /// The validated size, or nil while any code applies.
    public var size: CustomSize? {
        guard CustomSize.docs.contains(doc), errors.isEmpty, let maxKb else { return nil }
        return CustomSize(
            doc: doc, width: widthPx, height: heightPx, minKb: minKb ?? 0, maxKb: maxKb,
            dpi: dpi ?? CustomSize.defaultDpi
        )
    }
}

/// A validated Custom size (ALGORITHMS 11): what the file is and the form's limits. It becomes a synthetic slot so the
/// photo and ink flows run unchanged; `text` is the canonical form used for navigation and the recent sizes.
public struct CustomSize: Hashable, Sendable {
    /// Exam id and name of the synthetic exam: not translated, they name the file and the folder (§9.8).
    public static let examId = "custom"
    public static let examName = "Custom"
    public static let defaultDpi = 200
    public static let maxKbLimit = 5120
    public static let pxRange = 16...2000
    public static let dpiRange = 72...1200
    public static let docs: [DocType] = [.photo, .signature, .thumbImpression, .handwrittenDeclaration]
    /// Recent sizes kept on the device (ALGORITHMS 11).
    public static let recentLimit = 5

    public let doc: DocType
    public let width: Int?
    public let height: Int?
    public let minKb: Int
    public let maxKb: Int
    public let dpi: Int

    public var text: String {
        let dims = width.flatMap { w in height.map { "\(w)x\($0)" } } ?? "-"
        return "\(doc.rawValue);\(dims);\(minKb)-\(maxKb);\(dpi)"
    }

    /// The synthetic slot the flows run against.
    public var spec: DocSpec {
        let exact = width != nil && height != nil
        let dimensions = Dimensions(
            mode: exact ? .exact : .none, width: exact ? width : nil, height: exact ? height : nil,
            minW: nil, minH: nil, maxW: nil, maxH: nil, aspectWOverH: nil
        )
        return DocSpec(
            type: doc, required: true, formats: [.jpg],
            sizeKb: SizeKB(min: minKb > 0 ? Double(minKb) : nil, max: Double(maxKb)),
            dimensions: dimensions, dpi: dpi, physicalCm: nil, filename: nil, nameDateStrip: nil,
            declarationText: nil, notes: nil, rules: nil
        )
    }

    /// The size written as `text`, or nil unless it is exactly the canonical form of a valid size.
    public static func parse(_ text: String) -> CustomSize? {
        let parts = text.split(separator: ";", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, let doc = docs.first(where: { $0.rawValue == parts[0] }) else { return nil }
        var width: Int?, height: Int?
        if parts[1] != "-" {
            let sides = parts[1].split(separator: "x", omittingEmptySubsequences: false).map(String.init)
            guard sides.count == 2, let w = number(sides[0]), let h = number(sides[1]) else { return nil }
            width = w
            height = h
        }
        let window = parts[2].split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        guard window.count == 2, let min = number(window[0]), let max = number(window[1]),
              let dpi = number(parts[3]) else { return nil }
        return CustomSizeFields(
            doc: doc, width: width.map(Double.init), height: height.map(Double.init), minKb: min, maxKb: max, dpi: dpi
        ).size
    }

    /// `recent` with `text` moved to the front, distinct, at most `recentLimit`.
    public static func remember(_ recent: [String], _ text: String) -> [String] {
        Array(([text] + recent.filter { $0 != text }).prefix(recentLimit))
    }

    /// A canonical whole number: `0` or digits without a leading zero.
    private static func number(_ s: String) -> Int? {
        guard !s.isEmpty, s.allSatisfy(\.isASCII), s.allSatisfy(\.isNumber), s == "0" || !s.hasPrefix("0") else {
            return nil
        }
        return Int(s)
    }
}
