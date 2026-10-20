import DesignSystem
import ScanModel
import SwiftUI

/// Home (UI_UX §3, editor first): a title, a grid of large tool cards (Exam forms is one of them), the pinned exams
/// and the tools still to come. Two columns, or one at accessibility text sizes. The app target owns the stack and maps
/// each card's route, so Home never imports another feature (ARCHITECTURE §3).
public struct HomeView: View {
    private let model: HomeViewModel
    private let strings: Strings
    @Environment(\.dynamicTypeSize) private var typeSize

    public init(model: HomeViewModel, strings: Strings = Strings()) {
        self.model = model
        self.strings = strings
    }

    private struct Card: Identifiable {
        let id: String
        let title: String
        let body: String
        let symbol: String
        let accent: ToolAccent
        let route: Target?
    }

    /// Where a card goes; each opens a typed route, so the app's destinations match it.
    private enum Target {
        case exams, custom(DocType), pdf(String), check
    }

    /// In UI_UX §3 order: Exam forms first, then the everyday editors, the PDF tools and the Checker.
    private var tools: [Card] {
        [
            Card(id: "exams", title: strings.homeCardExams, body: strings.homeCardExamsBody,
                 symbol: "graduationcap.fill", accent: .exam, route: .exams),
            Card(id: "photo", title: strings.homeCardPhoto, body: strings.homeCardPhotoBody,
                 symbol: "photo.fill", accent: .photo, route: .custom(.photo)),
            Card(id: "signature", title: strings.homeCardSignature, body: strings.homeCardSignatureBody,
                 symbol: "signature", accent: .ink, route: .custom(.signature)),
            Card(id: "compress", title: strings.toolsPdfCompress, body: strings.toolsPdfCompressBody,
                 symbol: "arrow.down.right.and.arrow.up.left", accent: .pdf, route: .pdf("compress")),
            Card(id: "images", title: strings.toolsPdfImages, body: strings.toolsPdfImagesBody,
                 symbol: "doc.richtext.fill", accent: .pdf, route: .pdf("images")),
            Card(id: "merge", title: strings.toolsPdfMerge, body: strings.toolsPdfMergeBody,
                 symbol: "arrow.triangle.merge", accent: .pdf, route: .pdf("merge")),
            Card(id: "id_card", title: strings.toolsPdfIdCard, body: strings.toolsPdfIdCardBody,
                 symbol: "person.text.rectangle.fill", accent: .pdf, route: .pdf("id_card")),
            Card(id: "check", title: strings.homeCardCheck, body: strings.homeFixFileBody,
                 symbol: "checkmark.seal.fill", accent: .check, route: .check),
            Card(id: "thumb", title: strings.homeCardThumb, body: strings.homeCardThumbBody,
                 symbol: "touchid", accent: .ink, route: .custom(.thumbImpression)),
            Card(id: "document", title: strings.homeCardDocument, body: strings.homeCardDocumentBody,
                 symbol: "doc.text.fill", accent: .ink, route: .custom(.handwrittenDeclaration)),
        ]
    }

    private var comingSoon: [Card] {
        [
            Card(id: "scan", title: strings.toolsScan, body: strings.toolsScanBody, symbol: "doc.viewfinder",
                 accent: .muted, route: nil),
            Card(id: "guide", title: strings.toolsGuideSheet, body: strings.toolsGuideSheetBody, symbol: "printer.fill",
                 accent: .muted, route: nil),
            Card(id: "coach", title: strings.toolsCoach, body: strings.toolsCoachBody, symbol: "face.smiling",
                 accent: .muted, route: nil),
        ]
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.md) {
                header
                grid(tools)
                if !model.pinned.isEmpty { pinnedExams }
                sectionTitle(strings.homeComingSoonHeading)
                comingSoonList
            }
            .padding(.vertical, ScanFitSpacing.lg)
        }
        .background(ScanFitColor.background)
        .navigationTitle(strings.tabHome)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .navigationBar)
        #endif
            .task { await model.onAppear() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
            Text(strings.appName).scanFitText(.label).foregroundStyle(ScanFitColor.primary)
            Text(strings.homeTitle)
                .scanFitText(.display)
                .foregroundStyle(ScanFitColor.onBackground)
                .accessibilityAddTraits(.isHeader)
            Text(strings.homeSubtitle).scanFitText(.body).foregroundStyle(ScanFitColor.onSurfaceVariant)
        }
        .padding(.horizontal, ScanFitSpacing.screenMargin)
    }

    /// Rows of cards; every card in a row is as tall as the tallest.
    private func grid(_ cards: [Card]) -> some View {
        let columns = typeSize.isAccessibilitySize ? 1 : 2
        let rows = stride(from: 0, to: cards.count, by: columns).map {
            Array(cards[$0..<min($0 + columns, cards.count)])
        }
        return Grid(horizontalSpacing: ScanFitSpacing.md, verticalSpacing: ScanFitSpacing.md) {
            ForEach(rows, id: \.first?.id) { row in
                GridRow {
                    ForEach(row) { card in cell(card) }
                    if row.count < columns { Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]) }
                }
            }
        }
        .padding(.horizontal, ScanFitSpacing.screenMargin)
    }

    @ViewBuilder
    private func cell(_ card: Card) -> some View {
        let content = ToolCard(title: card.title, description: card.body, symbol: card.symbol, accent: card.accent)
        switch card.route {
        case .exams: NavigationLink(value: ExamBrowserRoute()) { content }.buttonStyle(.plain)
        case let .custom(doc): NavigationLink(value: CustomSizeRoute(doc: doc)) { content }.buttonStyle(.plain)
        case let .pdf(tool): NavigationLink(value: PdfRoute(tool: tool)) { content }.buttonStyle(.plain)
        case .check: NavigationLink(value: CheckerRoute()) { content }.buttonStyle(.plain)
        case nil: content
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .scanFitText(.headline)
            .foregroundStyle(ScanFitColor.onBackground)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, ScanFitSpacing.screenMargin)
            .padding(.top, ScanFitSpacing.md)
    }

    /// The tools still to come: one quiet card with a row each, not tappable.
    private var comingSoonList: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(comingSoon.enumerated()), id: \.element.id) { index, card in
                if index > 0 { Divider().padding(.leading, 56) }
                HStack(spacing: ScanFitSpacing.md) {
                    Image(systemName: card.symbol)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                        .background(ScanFitColor.outlineVariant.opacity(0.6), in: Circle())
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(card.title).scanFitText(.body).foregroundStyle(ScanFitColor.onSurface)
                        Text(card.body).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, ScanFitSpacing.md)
                .frame(minHeight: ScanFitSpacing.minTouchTarget)
                .accessibilityElement(children: .combine)
                .accessibilityHint(strings.commonComingSoon)
            }
        }
        .padding(.horizontal, ScanFitSpacing.lg)
        .background(ScanFitColor.surfaceVariant, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, ScanFitSpacing.screenMargin)
    }

    /// "My exams": the pinned exams as a row of pills; each opens its checklist.
    private var pinnedExams: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            sectionTitle(strings.homeSectionPinned)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: ScanFitSpacing.sm) {
                    ForEach(model.pinned, id: \.id) { exam in
                        NavigationLink(value: ExamRoute(examId: exam.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(exam.name).scanFitText(.body).foregroundStyle(ScanFitColor.onSurface)
                                Text(exam.body).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                            }
                            .padding(.horizontal, ScanFitSpacing.lg)
                            .padding(.vertical, ScanFitSpacing.md)
                            .frame(minHeight: ScanFitSpacing.minTouchTarget)
                            .background(ScanFitColor.surface, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
                            .overlay(RoundedRectangle(cornerRadius: ScanFitRadius.card)
                                .stroke(ScanFitColor.outlineVariant, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, ScanFitSpacing.screenMargin)
            }
        }
    }
}
