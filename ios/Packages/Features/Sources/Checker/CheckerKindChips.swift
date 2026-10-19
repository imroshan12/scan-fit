import DesignSystem
import Match
import SwiftUI

/// What the file is (ALGORITHMS 12) as wrapping chips, so every kind is visible at once at any font size (Android:
/// FilterChips in a FlowRow). The selected chip carries a tick, never colour alone.
struct CheckerKindChips: View {
    let selected: DocKind?
    let strings: Strings
    let onKind: (DocKind) -> Void

    var body: some View {
        WrappingLayout(spacing: ScanFitSpacing.sm) {
            ForEach(Self.kinds, id: \.self) { kind in
                let isSelected = kind == selected
                Button { onKind(kind) } label: {
                    Label {
                        Text(strings.label(kind)).scanFitText(.label)
                    } icon: {
                        if isSelected { Image(systemName: "checkmark").accessibilityHidden(true) }
                    }
                    .labelStyle(.titleAndIcon)
                    .padding(.horizontal, ScanFitSpacing.md)
                    .frame(minHeight: ScanFitSpacing.minTouchTarget)
                    .foregroundStyle(isSelected ? ScanFitColor.onPrimaryContainer : ScanFitColor.onSurfaceVariant)
                    .background(isSelected ? ScanFitColor.primaryContainer : ScanFitColor.surfaceVariant, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
    }

    static let kinds: [DocKind] = [.photo, .signature, .thumb, .fingers, .declaration, .pdfDocument]
}

/// Lays children left to right and starts a new row when the next one does not fit.
struct WrappingLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache _: inout ()) -> CGSize {
        let rows = rows(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map { row in row.reduce(0) { $0 + $1.width } + spacing * CGFloat(row.count - 1) }.max() ?? 0
        let height = rows.map { $0.map(\.height).max() ?? 0 }.reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal _: ProposedViewSize, subviews: Subviews, cache _: inout ()) {
        var index = 0
        var y = bounds.minY
        for row in rows(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            let height = row.map(\.height).max() ?? 0
            for size in row {
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
                index += 1
            }
            y += height + spacing
        }
    }

    /// Each child's size (never wider than the row), grouped into rows.
    private func rows(width: CGFloat, subviews: Subviews) -> [[CGSize]] {
        var rows: [[CGSize]] = [[]]
        var used: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(.unspecified)
            if size.width > width { size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil)) }
            if let last = rows.last, !last.isEmpty, used + spacing + size.width > width {
                rows.append([])
                used = 0
            }
            used += (rows[rows.count - 1].isEmpty ? 0 : spacing) + size.width
            rows[rows.count - 1].append(size)
        }
        return rows
    }
}
