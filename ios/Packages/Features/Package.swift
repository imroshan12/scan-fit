// swift-tools-version: 6.0
import PackageDescription

// Feature-sliced UI modules (ARCHITECTURE §3). Features depend on Core only, never on each other:
// navigation between features is composed in the app target.
let core: Target.Dependency = .product(name: "DesignSystem", package: "Core")
let presets: Target.Dependency = .product(name: "Presets", package: "Core")
let model: Target.Dependency = .product(name: "ScanModel", package: "Core")

let package = Package(
    name: "Features",
    defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "Home", targets: ["Home"]),
        .library(name: "Exams", targets: ["Exams"]),
        .library(name: "PhotoFlow", targets: ["PhotoFlow"]),
        .library(name: "InkFlow", targets: ["InkFlow"]),
        .library(name: "Coach", targets: ["Coach"]),
        .library(name: "Checker", targets: ["Checker"]),
        .library(name: "Custom", targets: ["Custom"]),
        .library(name: "PDFTools", targets: ["PDFTools"]),
        .library(name: "Kit", targets: ["Kit"]),
        .library(name: "Paywall", targets: ["Paywall"]),
        .library(name: "Settings", targets: ["Settings"]),
    ],
    dependencies: [.package(path: "../Core")],
    targets: [
        // Phase 0 content
        .target(name: "Home", dependencies: [core, presets]),
        .target(name: "Kit", dependencies: [core]),
        .target(name: "Settings", dependencies: [core, presets]),
        // Empty until their phase (ROADMAP Phase 2-4)
        .target(name: "Exams", dependencies: [core, model]),
        .target(name: "PhotoFlow", dependencies: [core, model]),
        .target(name: "InkFlow", dependencies: [core, model]),
        .target(name: "Coach", dependencies: [core, model]),
        .target(name: "Checker", dependencies: [core, model]),
        .target(name: "Custom", dependencies: [core, model]),
        .target(name: "PDFTools", dependencies: [core, model]),
        .target(name: "Paywall", dependencies: [core]),
        .testTarget(name: "HomeTests", dependencies: ["Home", presets]),
    ],
    swiftLanguageModes: [.v6]
)
