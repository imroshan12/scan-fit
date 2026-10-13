import DesignSystem
import ScanData
import ScanModel
import SwiftUI

#if DEBUG
/// Empty first visit, a filled form in centimetres with recent sizes, and Continue tried with errors.
@MainActor
private enum CustomPreviewModels {
    static func empty() -> CustomViewModel { CustomViewModel(preferences: nil) }

    static func filled() -> CustomViewModel {
        let name = "scanfit.preview.custom"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        let prefs = UserPreferences(defaults: defaults)
        prefs.rememberCustomSize("photo;-;0-50;200")
        prefs.rememberCustomSize("signature;140x60;10-20;200")
        let model = CustomViewModel(preferences: prefs)
        model.form = CustomForm(
            doc: .photo, unit: .cm, width: "3.5", height: "4.5", minKb: "20", maxKb: "50", dpi: "200"
        )
        return model
    }

    static func errors() -> CustomViewModel {
        let model = CustomViewModel(preferences: nil)
        model.form = CustomForm(doc: .signature, width: "140", minKb: "30", maxKb: "20")
        _ = model.continueTapped()
        return model
    }
}

private struct CustomPreview: View {
    let model: CustomViewModel
    let strings: Strings

    var body: some View {
        NavigationStack { CustomView(model: model, strings: strings) { _ in } }
    }
}

#Preview("Custom - empty") {
    CustomPreview(model: CustomPreviewModels.empty(), strings: Strings(languageCode: "en"))
}

#Preview("Custom - filled, dark") {
    CustomPreview(model: CustomPreviewModels.filled(), strings: Strings(languageCode: "en")).preferredColorScheme(.dark)
}

#Preview("Custom - errors, Hindi largest") {
    CustomPreview(model: CustomPreviewModels.errors(), strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}
#endif
