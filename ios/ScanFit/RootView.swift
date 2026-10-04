import DesignSystem
import Exams
import Home
import Kit
import Presets
import ScanModel
import Settings
import SwiftUI

/// Tab shell: Home · My Kit · Tools · Settings (UI_UX §2). The Home stack maps `ExamRoute` to the exam screen, so the
/// two features never import each other.
struct RootView: View {
    private let container: AppContainer
    @State private var homeModel: HomeViewModel
    @State private var presets: PresetsSummary?

    init(container: AppContainer) {
        self.container = container
        _homeModel = State(initialValue: HomeViewModel(preferences: container.preferences) {
            let outcome = await container.presetsOutcome.value
            return HomeViewModel.Loaded(outcome: outcome, bundle: await container.trustedBundle())
        })
    }

    var body: some View {
        let strings = container.strings
        TabView {
            NavigationStack {
                HomeView(model: homeModel, strings: strings)
                    .navigationDestination(for: ExamRoute.self) { route in
                        ExamView(
                            model: ExamViewModel(examId: route.examId, preferences: container.preferences) {
                                await container.trustedBundle()
                            },
                            strings: strings
                        )
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
