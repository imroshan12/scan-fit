# ScanFit (working name)

Exam and government-form uploads, done right on the phone. Photo, signature, thumb, declaration
and certificates, fitted to each exam's exact rules, verified, and matched to every exam that
accepts them. Offline. Native Android (Kotlin/Compose) and iOS (Swift/SwiftUI) in one repo,
sharing a spec rather than code.

| Doc | Purpose |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Rules and commands for Claude Code: read first |
| [docs/PRD.md](docs/PRD.md) | Product, users, features by release, monetisation |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Both apps' architecture, modules, data, presets delivery, security, budgets, CI/CD |
| [spec/ALGORITHMS.md](spec/ALGORITHMS.md) | Behaviour contract: fit engine, cleanup, match engine, inspector, PDF, live coach |
| [docs/TECH_FEASIBILITY.md](docs/TECH_FEASIBILITY.md) | Every tool/API per feature, what's possible, spikes, dependency allow-list |
| [docs/UI_UX.md](docs/UI_UX.md) | IA, screens, design system, motion, accessibility |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phases, tasks, exit gates |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy, device matrix, release gates |
| [docs/RELEASE_AND_GTM.md](docs/RELEASE_AND_GTM.md) | Rollout, ASO, marketing, competition, KPIs |
| [spec/README.md](spec/README.md) | How exam presets work and how to update them |

## Getting started
```bash
pip install -r spec/tools/requirements.txt
spec/tools/build_all.sh                       # builds + dev-signs the presets both apps embed

(cd android && ./gradlew spotlessCheck detekt testDebugUnitTest :app:assembleDebug)   # needs android/local.properties: sdk.dir=…
(cd ios && xcodegen generate && xcodebuild -scheme ScanFit -destination 'platform=iOS Simulator,name=<a simulator you have>' test)
swift test --package-path ios/Packages/Core
```
Then the current phase in `docs/ROADMAP.md` (status notes are inline). Before the first release: create the production signing key
(`spec/signing/README.md`) and confirm the app ids (`app.scanfit` on Android, `app.scanfit.ios` on iOS).

## Troubleshooting
- **Gradle fails with "implementation class `Scanfit_…Plugin` was not found in the jar", or an unresolved `java {}` accessor in a
  convention plugin.** Another Gradle (typically **VS Code's Java extension** importing `android/` with its own, older Gradle) was
  compiling `android/build-logic` at the same time. `build-logic` now writes to `build/gradle-<version>/` so two Gradles do not share
  output, but the IDE import is still unwanted: set `"java.import.gradle.enabled": false` in the workspace settings, delete
  `android/**/bin/` (the extension's output, git-ignored) and run `./gradlew --stop`. If a bad result was cached, change any
  `build-logic` source file (a comment is enough) or run once with `--no-build-cache --rerun-tasks`.
- **`spotlessApply` changes nothing / reports lints about files under `build/`.** Spotless is pointed at an explicit `fileTree`
  in `android/build.gradle.kts`; generated sources must never be formatted. Run it with the daemon stopped if in doubt.
- **`xcodebuild … name=iPhone 16` finds no device.** Use a simulator you have (`xcrun simctl list devices available`), e.g. `iPhone 17`.
