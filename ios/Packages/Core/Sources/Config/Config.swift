import Foundation

/// The website pages and support address the app opens (Settings, paywall; App Store guideline 3.1.2). The site is
/// `web/`, published to GitHub Pages by `.github/workflows/pages.yml` (user decision 2026-10-20). Keep in step with
/// Android `AppLinks`.
/// Remote Config defaults and fetch come here next (ARCHITECTURE §9).
public enum AppLinks {
    public static let privacyPolicy = "https://imroshan12.github.io/scan-fit/privacy/"
    public static let terms = "https://imroshan12.github.io/scan-fit/terms/"
    public static let help = "https://imroshan12.github.io/scan-fit/help/"
    public static let supportEmail = "pro.sarvesh.roshan@gmail.com"
}
