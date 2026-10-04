import DesignSystem
import Exams
import Home
import Kit
import PhotoFlow
import Presets
import ScanModel
import Settings
import SwiftUI

/// Tab shell: Home · My Kit · Tools · Settings (UI_UX §2). The Home stack maps `ExamRoute` to the exam screen and
/// `PhotoRoute` to the photo flow, so the features never import each other.
struct RootView: View {
    private let container: AppContainer
    @State private var homeModel: HomeViewModel
    @State private var presets: PresetsSummary?
    /// The Home stack's path, so a flow can leave itself explicitly (see `PhotoFlowView.onExit`).
    @State private var homePath = NavigationPath()

    init(container: AppContainer) {
        self.container = container
        _homeModel = State(initialValue: HomeViewModel(preferences: container.preferences) {
            let outcome = await container.presetsOutcome.value
            return HomeViewModel.Loaded(outcome: outcome, bundle: await container.trustedBundle())
        })
    }

    /// Document types the photo flow handles (ALGORITHMS 1.2: the kinds that are cropped, not padded).
    private static let photoFlowTypes: Set<DocType> = [.photo, .postcardPhoto]

    var body: some View {
        let strings = container.strings
        TabView {
            NavigationStack(path: $homePath) {
                HomeView(model: homeModel, strings: strings)
                    .navigationDestination(for: ExamRoute.self) { route in
                        ExamView(
                            model: ExamViewModel(examId: route.examId, preferences: container.preferences) {
                                await container.trustedBundle()
                            },
                            strings: strings,
                            openable: Self.photoFlowTypes
                        )
                    }
                    .navigationDestination(for: PhotoRoute.self) { route in
                        PhotoFlowView(
                            model: PhotoFlowViewModel(
                                examId: route.examId, docType: route.docType, preferences: container.preferences
                            ) {
                                await container.trustedBundle()
                            },
                            strings: strings
                        ) {
                            if !homePath.isEmpty { homePath.removeLast() }
                        }
                    }
            }
            .tabItem { Label(strings.tabHome, systemImage: "house") }
            KitView(strings: strings)
                .tabItem { Label(strings.tabKit, systemImage: "tray.full") }
            ToolsView(strings: strings)
                .tabItem { Label(strings.tabTools, systemImage: "wrench.and.screwdriver") }
            SettingsView(presets: presets, preferences: container.preferences, strings: strings)
                .tabItem { Label(strings.tabSettings, systemImage: "gearshape") }
        }
        .tint(ScanFitColor.primary)
        .task {
            if case let .ready(summary) = await container.presetsOutcome.value {
                presets = summary
            }
        }
    }
}
