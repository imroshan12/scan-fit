import DesignSystem
import Home
import Kit
import Presets
import Settings
import SwiftUI

/// Tab shell: Home · My Kit · Tools · Settings (UI_UX §2).
struct RootView: View {
    private let container: AppContainer
    @State private var homeModel: HomeViewModel
    @State private var presets: PresetsSummary?

    init(container: AppContainer) {
        self.container = container
        let outcome = container.presetsOutcome
        _homeModel = State(initialValue: HomeViewModel { await outcome.value })
    }

    var body: some View {
        let strings = container.strings
        TabView {
            HomeView(model: homeModel, strings: strings)
                .tabItem { Label(strings.tabHome, systemImage: "house") }
            KitView(strings: strings)
                .tabItem { Label(strings.tabKit, systemImage: "tray.full") }
            ToolsView(strings: strings)
                .tabItem { Label(strings.tabTools, systemImage: "wrench.and.screwdriver") }
            SettingsView(presets: presets, strings: strings)
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
