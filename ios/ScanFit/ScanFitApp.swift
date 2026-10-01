import SwiftUI

@main
struct ScanFitApp: App {
    @State private var container = AppContainer.live()

    var body: some Scene {
        WindowGroup {
            RootView(container: container)
                .environment(\.appContainer, container)
        }
    }
}
