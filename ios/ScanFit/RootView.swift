import Checker
import DesignSystem
import Custom
import Exams
import Home
import Kit
import InkFlow
import PhotoFlow
import Presets
import ScanData
import ScanModel
import Settings
import SwiftUI

/// Tab shell: Home · My Kit · Tools · Settings (UI_UX §2). The Home stack maps `ExamRoute` to the exam screen and
/// `FlowRoute` to the photo or ink flow, so the features never import each other.
struct RootView: View {
    private let container: AppContainer
    @State private var homeModel: HomeViewModel
    @State private var kitModel: KitViewModel
    @State private var kitOriginals: KitOriginalsViewModel
    @State private var kitHistory: KitHistoryViewModel
    @State private var presets: PresetsSummary?
    /// The Home stack's path, so a flow can leave itself explicitly (see `PhotoFlowView.onExit`).
    @State private var homePath = NavigationPath()
    @State private var kitPath = NavigationPath()
    @State private var toolsPath = NavigationPath()
    @State private var selectedTab = Tab.home

    private enum Tab: Hashable { case home, kit, tools, settings }

    init(container: AppContainer) {
        self.container = container
        _homeModel = State(initialValue: HomeViewModel(preferences: container.preferences) {
            let outcome = await container.presetsOutcome.value
            return HomeViewModel.Loaded(outcome: outcome, bundle: await container.trustedBundle())
        })
        _kitModel = State(initialValue: KitViewModel(drafts: container.drafts) {
            await container.trustedBundle()
        })
        _kitOriginals = State(initialValue: KitOriginalsViewModel(kit: container.kit))
        _kitHistory = State(initialValue: KitHistoryViewModel(kit: container.kit))
    }

    /// Document types the photo flow handles (ALGORITHMS 1.2: the kinds that are cropped, not padded).
    private static let photoFlowTypes: Set<DocType> = [.photo, .postcardPhoto]
    /// Document types the ink flow handles (ALGORITHMS 3 / 9.5: cleaned up, padded to aspect).
    private static let inkFlowTypes: Set<DocType> = [
        .signature, .tripleSignature, .leftThumb, .thumbImpression,
        .leftHandFingersThumb, .rightHandFingersThumb, .handwrittenDeclaration,
    ]

    var body: some View {
        let strings = container.strings
        TabView(selection: $selectedTab) {
            NavigationStack(path: $homePath) {
                HomeView(model: homeModel, strings: strings)
                    .navigationDestination(for: ExamRoute.self) { route in
                        ExamView(
                            model: ExamViewModel(
                                examId: route.examId, preferences: container.preferences, drafts: container.drafts,
                                kit: container.kit
                            ) {
                                await container.trustedBundle()
                            },
                            strings: strings,
                            openable: Self.photoFlowTypes.union(Self.inkFlowTypes)
                        )
                    }
                    .navigationDestination(for: FlowRoute.self) { route in
                        flow(route, strings: strings) {
                            if !homePath.isEmpty { homePath.removeLast() }
                        }
                    }
                    .navigationDestination(for: CustomSizeRoute.self) { _ in
                        customSize(strings: strings) { homePath.append(FlowRoute(custom: $0)) }
                    }
                    .navigationDestination(for: CheckerRoute.self) { _ in checker(strings: strings) }
            }
            .tabItem { Label(strings.tabHome, systemImage: "house") }
            .tag(Tab.home)
            NavigationStack(path: $kitPath) {
                KitView(
                    model: kitModel, originals: kitOriginals, history: kitHistory, strings: strings,
                    isActive: selectedTab == .kit
                )
                .navigationDestination(for: FlowRoute.self) { route in
                    flow(route, strings: strings) {
                        if !kitPath.isEmpty { kitPath.removeLast() }
                    }
                }
                .navigationDestination(for: KitOriginalRoute.self) { route in
                    // "Use for exam…" (ALGORITHMS 13.2) opens the slot's flow on this stack; Done comes back here.
                    KitOriginalView(
                        model: KitOriginalViewModel(id: route.id, kit: container.kit) {
                            await container.trustedBundle()
                        },
                        strings: strings,
                        onUse: { kitPath.append($0) },
                        onLeave: { if !kitPath.isEmpty { kitPath.removeLast() } }
                    )
                }
            }
            .tabItem { Label(strings.tabKit, systemImage: "tray.full") }
            .tag(Tab.kit)
            NavigationStack(path: $toolsPath) {
                ToolsView(strings: strings)
                    .navigationDestination(for: CustomSizeRoute.self) { _ in
                        customSize(strings: strings) { toolsPath.append(FlowRoute(custom: $0)) }
                    }
                    .navigationDestination(for: CheckerRoute.self) { _ in checker(strings: strings) }
                    .navigationDestination(for: FlowRoute.self) { route in
                        flow(route, strings: strings) {
                            if !toolsPath.isEmpty { toolsPath.removeLast() }
                        }
                    }
            }
            .tabItem { Label(strings.tabTools, systemImage: "wrench.and.screwdriver") }
            .tag(Tab.tools)
            SettingsView(presets: presets, preferences: container.preferences, strings: strings)
                .tabItem { Label(strings.tabSettings, systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .tint(ScanFitColor.primary)
        .task {
            // A file staged for the share sheet never outlives the session (ALGORITHMS 1.6.2).
            await ShareStager.clear()
            if case let .ready(summary) = await container.presetsOutcome.value {
                presets = summary
            }
        }
    }

    /// The Custom size form (ALGORITHMS 11); Continue opens the photo or ink flow for the size on the same stack.
    private func customSize(strings: Strings, open: @escaping (CustomSize) -> Void) -> some View {
        CustomView(model: CustomViewModel(preferences: container.preferences), strings: strings, onContinue: open)
    }

    /// The pre-submit Checker (ALGORITHMS 12): a file the user already has, against the exam slot they choose.
    private func checker(strings: Strings) -> some View {
        CheckerView(
            model: CheckerViewModel(preferences: container.preferences, kit: container.kit) {
                await container.trustedBundle()
            },
            strings: strings
        )
    }

    /// The photo flow for photo slots, the ink flow for everything else the exam screen lets the user open.
    @ViewBuilder
    private func flow(_ route: FlowRoute, strings: Strings, onExit: @escaping () -> Void) -> some View {
        if Self.photoFlowTypes.contains(route.docType) {
            PhotoFlowView(
                model: PhotoFlowViewModel(
                    target: route.target, preferences: container.preferences, drafts: container.drafts,
                    kit: container.kit, originalID: route.original
                ) {
                    await container.trustedBundle()
                },
                strings: strings,
                onExit: onExit
            )
        } else {
            InkFlowView(
                model: InkFlowViewModel(
                    target: route.target, preferences: container.preferences, drafts: container.drafts,
                    kit: container.kit, originalID: route.original
                ) {
                    await container.trustedBundle()
                },
                strings: strings,
                onExit: onExit
            )
        }
    }
}
