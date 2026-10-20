import Imaging
import ScanModel
import SwiftUI

/// "Save to My Kit" under Save and Share on a photo or ink review (ALGORITHMS 13.1): keeps the picture the review was
/// made from, not the fitted file. With `preview`, a tap first shows that picture and saves only after "Save to My Kit"
/// (ALGORITHMS 13.3 previews); Cancel changes nothing. A full Kit or a failure is explained under the button.
public struct SaveToKitButton: View {
    private let status: KitSaveStatus
    private let strings: Strings
    private let preview: (@MainActor () async -> Raster?)?
    private let action: () -> Void
    @State private var confirming = false
    @State private var picture: Raster?

    public init(
        status: KitSaveStatus, strings: Strings, preview: (@MainActor () async -> Raster?)? = nil,
        action: @escaping () -> Void
    ) {
        self.status = status
        self.strings = strings
        self.preview = preview
        self.action = action
    }

    public var body: some View {
        VStack(spacing: ScanFitSpacing.xs) {
            Button { if preview != nil { confirming = true } else { action() } } label: {
                HStack(spacing: ScanFitSpacing.sm) {
                    switch status {
                    case .saving: ProgressView()
                    case .saved: Image(systemName: "checkmark.circle.fill").accessibilityHidden(true)
                    default: EmptyView()
                    }
                    Text(title)
                }
                .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
            }
            .disabled(status == .saving || status == .saved)
            if let problem {
                Label(problem, systemImage: "exclamationmark.circle")
                    .scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.error)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
            }
        }
        .sheet(isPresented: $confirming) { confirmation }
    }

    /// The picture that would be kept, with Save to My Kit and Cancel.
    private var confirmation: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.md) {
            Text(strings.kitSavePreviewTitle).scanFitText(.title).accessibilityAddTraits(.isHeader)
            ZStack {
                ScanFitColor.surfaceVariant
                if let picture {
                    RasterImage(raster: picture)
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, minHeight: 200, maxHeight: 320)
            .clipShape(RoundedRectangle(cornerRadius: ScanFitRadius.card))
            Text(strings.kitSavePreviewBody).scanFitText(.body)
            Button {
                confirming = false
                action()
            } label: {
                Text(strings.kitSaveOriginal).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
            }
            .buttonStyle(.borderedProminent)
            Button(strings.commonCancel) { confirming = false }
                .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
        }
        .padding(ScanFitSpacing.screenMargin)
        .presentationDetents([.medium, .large])
        .task { picture = await preview?() }
    }

    private var title: String {
        switch status {
        case .saving: strings.kitSavingOriginal
        case .saved: strings.kitSavedOriginal
        default: strings.kitSaveOriginal
        }
    }

    private var problem: String? {
        switch status {
        case .full: strings.kitFull
        case .failed: strings.kitSaveFailed
        default: nil
        }
    }
}

private struct SaveToKitPreviews: View {
    let strings: Strings

    var body: some View {
        VStack(spacing: ScanFitSpacing.lg) {
            ForEach(KitSaveStatus.allCases, id: \.self) { SaveToKitButton(status: $0, strings: strings) {} }
        }
        .padding(ScanFitSpacing.screenMargin)
    }
}

#Preview("Save to Kit - light") {
    SaveToKitPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Save to Kit - dark") {
    SaveToKitPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.dark)
}

#Preview("Save to Kit - Hindi largest") {
    SaveToKitPreviews(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}
