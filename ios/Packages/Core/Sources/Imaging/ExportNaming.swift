import Foundation
import ScanModel

/// Export file names (ALGORITHMS 1.7 / 9.8). The folder (`Files > ScanFit > <Exam>/`) is chosen by the export layer.
public enum ExportNaming {
    /// `IBPS PO / MT` -> `IBPS-PO`.
    public static func examShort(_ name: String) -> String {
        var cut = name
        for separator in [" / ", " ("] {
            if let range = cut.range(of: separator) { cut = String(cut[..<range.lowerBound]) }
        }
        var out = ""
        var pendingDash = false
        for scalar in cut.unicodeScalars {
            let code = scalar.value
            let isAlnum = (48...57).contains(code) || (65...90).contains(code) || (97...122).contains(code)
            if isAlnum {
                if pendingDash && !out.isEmpty { out.append("-") }
                pendingDash = false
                out.unicodeScalars.append(scalar)
            } else {
                pendingDash = true
            }
        }
        return out
    }

    public static func fileName(exam: Exam, slot: DocSpec, width: Int, height: Int, bytes: Int) -> String {
        if let filename = slot.filename { return "\(filename).jpg" }
        let kb = roundHalfUp(Double(bytes) / 1024.0)
        return "\(slot.type.rawValue)_\(examShort(exam.name))_\(width)x\(height)_\(kb)kb.jpg"
    }
}
