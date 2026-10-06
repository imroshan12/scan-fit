# TESTING.md

## 1. Pyramid

| Layer                | Android                                                                                     | iOS                                                              | What                                                                                                              |
| -------------------- | ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Unit (engines, pure) | JUnit on JVM (`core:model/match/inspect`), Robolectric for imaging                          | Swift Testing in `Packages/Core`                                 | Inspector, JpegPatcher, geometry, fit search, match, framing math, PDF layout                                     |
| **Conformance**      | `ConformanceTest` over `spec/fixtures/cases.json`                                           | `ConformanceTests`                                               | Cross-platform parity; runs on every PR touching engines or spec                                                  |
| Property-based       | kotest-property or hand-rolled generators                                                   | Swift Testing parameterised + seeded random                      | fit(any image, any slot) → output always passes that slot or returns a typed error; match(fit output) ⊇ that slot |
| Integration          | Room DAO + migrations (`MigrationTestHelper`), ExportRepository on an emulator (MediaStore) | SwiftData container in-memory, file exporter                     | Persistence, verify-after-write                                                                                   |
| UI                   | Compose UI tests (key journeys)                                                             | XCUITest (key journeys)                                          | Journeys below                                                                                                    |
| Performance          | Macrobenchmark (startup, frame timing), Microbenchmark (fit)                                | XCTest `measure` + `XCTOSSignpostMetric`, Instruments            | Budgets in ARCHITECTURE §11                                                                                       |
| Accessibility        | Espresso `AccessibilityChecks` + manual TalkBack                                            | `XCUIApplication.performAccessibilityAudit()` + manual VoiceOver |                                                                                                                   |

### No screenshot tests

Removed on 2026-10-03 by product decision: recording and verifying image baselines made every UI change slow.
Screens are checked instead by Compose `@Preview`s / SwiftUI `#Preview`s in light, dark, the largest font scale and Hindi, by
hand on the low-end profiles, and by view-model unit tests for every state. Formatting that depends on the locale (dates,
numbers) gets a unit test in Hindi.

Photo export tests on both platforms consume `spec/fixtures/cases.json` `export_cases`: success, cancelled picker,
write/read failures, corrupt bytes, wrong DPI, private metadata and failed publication. Additional tests cover duplicate taps,
busy edit/back guards, stale debounced renders, failure retry, best-effort cleanup and Saved persistence. iOS transport tests
use actual temporary files with coordinated reading/removal; Android provider/device integration still needs the matrix below.
Run SwiftLint from `ios/`, and rebuild/sign the local dev preset bundle together before full preset suites.

iOS Files lifecycle regressions explicitly deliver sheet dismissal before the save/cancel delegate result. Dismissal alone
must not resolve the export or delete its staged source. A real-exporter integration test verifies the destination then checks
Review's Saved state, the existing exam's preferences and status loaded after relaunch.

Retained draft tests on both platforms consume all 11 `draft_cases` using real private temporary files. They cover
relaunch byte equality, expiry boundaries/future timestamps, current preset changes, corruption, DPI/privacy checks,
unsupported records, oversized input, symlinks and atomic replacement failures. Feature tests cover Ready/Saved
precedence, reactive refresh, retired exams, restored review without processing, handwriting confirmation, Replace/Back,
retention failure with immediate Save, cancelled/superseded retention and unchanged exported bytes. iOS also exercises
restored Files export with dismissal preceding the completion delegate. Device disk-full/provider/backup QA remains open.

## 2. Key journeys (UI tests, both platforms)

1. First launch → search "ibps po" → checklist → photo from gallery (fixture injected via a fake
   picker) → review shows `✓ Accepted by ≥ 10 exams` → Save → file exists and verifies.
2. Signature from the paper fixture → cleanup → near-miss fix on another exam → second file saved.
3. Custom resize 20–50 KB 200×230 → match note lists the IBPS family.
4. Checker on `cmyk_photo_200x230.jpg` → CMYK issue → Fix → re-check passes.
5. Free limit reached → paywall → rewarded path (fake ad) → export allowed.
6. Offline launch (airplane mode) → embedded presets → full flow works.
7. Tampered preset bundle from a fake CDN → rejected, old bundle retained, no crash.
8. Hindi locale → the whole journey 1 with no truncation.

## 3. Test doubles

Every platform service sits behind an interface with a fake in `core:testing` / `TestSupport`:
`FaceDetector`, `Segmenter`, `DocumentScanner`, `ImagePicker`, `Clock`, `PresetRemote`,
`Entitlements`, `AdGate`, `Analytics` (records events, and asserts no event param contains a
path/URI — a test helper fails if any string param contains `/` or `content:`).

## 4. Device matrix (manual + automated smoke before each release)

| Tier    | Android                                                                                                                                                       | iOS                                                     |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| Low     | 4 GB RAM, Android 10–12 (e.g. an entry Redmi/Samsung A0x/Realme C-series)                                                                                     | iPhone 11 Pro Max on iOS 17 (the minimum)               |
| Mid     | 6 GB, Android 14–15 (Samsung A5x, Redmi Note)                                                                                                                 | iPhone 13/14                                            |
| High    | Pixel (latest), Samsung S-series (Android 16)                                                                                                                 | latest iPhone on the latest iOS                         |
| Special | Samsung One UI (Downloads + Samsung Internet upload path), a Xiaomi HyperOS device (background restrictions), a device without Play services (fallback paths) | iPad (the layout must not break, even if not optimised) |

## 5. Portal-fidelity QA (every release and every preset change)

- Upload every fixture output through the `web/upload-test/` echo page on Android Chrome,
  Samsung Internet and iOS Safari, from each save location. Bytes, dimensions and SOF must be
  unchanged.
- Quarterly (and when a big exam opens): run the real portal's client-side validator where it's
  reachable before submission, for the top 10 exams. Record results in `docs/portal-log.md`.

## 6. Closed-test tester script (Play 14-day test / TestFlight)

Day 1: install, pick 2 exams you might apply to, make all files. Day 3: use the Checker on an old
file from your gallery. Day 5: custom resize for any other form. Day 8: PDF compress a
certificate. Day 12: the live coach. Report anything confusing in the in-app feedback link.
Testers must stay opted in for the full 14 days and open the app on several days: Google reviews
real engagement, not just opt-ins.

## 7. Release gates (all must be green)

- CI: unit, conformance, lint, detekt/SwiftLint, licence check.
- Performance budgets met on reference low-end + mid devices.
- Crash-free sessions ≥ 99.5% in beta over ≥ 200 sessions; no ANRs in the beta cohort.
- Portal-fidelity QA passed.
- Manual smoke on the device matrix (30 min per tier) with the checklist in `docs/release-checklist.md`
  (create in Phase 5 from RELEASE_AND_GTM §2).
- Presets: `build_presets.py` green, `VERSION` bumped, signature verified by both apps.
