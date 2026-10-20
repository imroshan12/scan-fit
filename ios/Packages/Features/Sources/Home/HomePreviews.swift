import DesignSystem
import Foundation
import ScanData
import SwiftUI

#if DEBUG
@MainActor
private func previewModel() -> HomeViewModel {
    let defaults = UserDefaults(suiteName: "scanfit.preview.home") ?? .standard
    return HomeViewModel(preferences: UserPreferences(defaults: defaults)) {
        HomeViewModel.Loaded(outcome: .missingEmbedded, bundle: nil)
    }
}

#Preview("Home - light") {
    NavigationStack { HomeView(model: previewModel(), strings: Strings(languageCode: "en")) }
}

#Preview("Home - dark") {
    NavigationStack { HomeView(model: previewModel(), strings: Strings(languageCode: "en")) }
        .preferredColorScheme(.dark)
}

#Preview("Home - Hindi largest") {
    NavigationStack { HomeView(model: previewModel(), strings: Strings(languageCode: "hi")) }
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}

#Preview("Exam forms - light") {
    NavigationStack { ExamBrowserView(model: previewModel(), strings: Strings(languageCode: "en")) }
}
#endif
