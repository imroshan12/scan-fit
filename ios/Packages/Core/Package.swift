// swift-tools-version: 6.0
import PackageDescription

// Core engines and services (ARCHITECTURE §3). Three modules are prefixed because their natural
// names collide with Apple frameworks or stdlib types: ScanModel, ScanData, ScanVision.
// Engines stay free of UI; only DesignSystem imports SwiftUI.
let package = Package(
    name: "Core",
    defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "DesignSystem", targets: ["DesignSystem"]),
        .library(name: "ScanModel", targets: ["ScanModel"]),
        .library(name: "Presets", targets: ["Presets"]),
        .library(name: "Imaging", targets: ["Imaging"]),
        .library(name: "Match", targets: ["Match"]),
        .library(name: "Inspect", targets: ["Inspect"]),
        .library(name: "PDF", targets: ["PDF"]),
        .library(name: "ScanVision", targets: ["ScanVision"]),
        .library(name: "ScanData", targets: ["ScanData"]),
        .library(name: "Billing", targets: ["Billing"]),
        .library(name: "Ads", targets: ["Ads"]),
        .library(name: "Analytics", targets: ["Analytics"]),
        .library(name: "Config", targets: ["Config"]),
        .library(name: "TestSupport", targets: ["TestSupport"]),
    ],
    targets: [
        // Phase 0 content
        .target(name: "ScanModel"),
        .target(name: "DesignSystem", dependencies: ["ScanModel"], resources: [.process("Resources")]),
        .target(name: "Presets", dependencies: ["ScanModel"]),
        .target(name: "Analytics"),
        .target(name: "TestSupport", dependencies: ["Imaging", "ScanVision", "ScanModel"]),
        // Empty until their phase (ROADMAP): engines in Phase 1, services in Phase 3-4.
        .target(name: "Imaging", dependencies: ["ScanModel", "Inspect"]),
        .target(name: "Match", dependencies: ["ScanModel", "Inspect"]),
        .target(name: "Inspect", dependencies: ["ScanModel"]),
        .target(name: "PDF", dependencies: ["ScanModel"]),
        .target(name: "ScanVision", dependencies: ["ScanModel", "Imaging"]),
        .target(name: "ScanData", dependencies: ["ScanModel"]),
        .target(name: "Billing"),
        .target(name: "Ads"),
        .target(name: "Config"),
        // Tests
        .testTarget(name: "ScanModelTests", dependencies: ["ScanModel", "TestSupport"]),
        .testTarget(name: "PresetsTests", dependencies: ["Presets", "ScanModel", "TestSupport"]),
        .testTarget(name: "DesignSystemTests", dependencies: ["DesignSystem", "TestSupport"]),
        .testTarget(name: "ScanDataTests", dependencies: ["ScanData"]),
        .testTarget(name: "AnalyticsTests", dependencies: ["Analytics"]),
        .testTarget(name: "InspectTests", dependencies: ["Inspect", "TestSupport"]),
        .testTarget(name: "ScanVisionTests", dependencies: ["ScanVision", "Imaging", "TestSupport"]),
        .testTarget(name: "MatchTests", dependencies: ["Match", "Inspect", "ScanModel", "TestSupport"]),
        .testTarget(name: "ImagingTests", dependencies: ["Imaging", "Inspect", "Match", "ScanModel", "TestSupport"]),
    ],
    swiftLanguageModes: [.v6]
)
