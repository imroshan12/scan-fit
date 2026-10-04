# CLAUDE.md — ScanFit (working name)

Offline-first mobile app that turns phone photos of photos, signatures, thumb impressions,
declarations and certificates into files that pass Indian exam and government portal upload rules.
Two **fully native** apps in one repo, sharing a data and behaviour spec, not code.

Read these before changing anything:

| When you are… | Read |
|---|---|
| Starting any task | `docs/ROADMAP.md` (find the current phase and its tasks), this file |
| Touching image, PDF, match or face logic | `spec/ALGORITHMS.md` — the single source of truth for behaviour on both platforms |
| Adding or changing a screen | `docs/UI_UX.md` |
| Structuring code, adding a module or dependency | `docs/ARCHITECTURE.md`, `docs/TECH_FEASIBILITY.md` |
| Editing exam presets | `spec/README.md` |
| Building, signing, CI or releasing | `docs/BUILD_AND_RELEASE.md` (concepts + how-to), `docs/RELEASE_CHECKLIST.md` (to-do list) |
| Writing tests | `docs/TESTING.md` |

## Repo layout

```
android/        Kotlin + Jetpack Compose app (Gradle, multi-module)
ios/            Swift + SwiftUI app (Xcode project + local Swift packages)
spec/           Shared contract: presets, schema, algorithms, fixtures, strings, design tokens
  presets/exams/<category>/<id>.json   one file per exam (source of truth)
  schema/       JSON Schema for presets
  fixtures/     test images + cases.json (conformance suite both apps must pass)
  strings/      en.json, hi.json → generated into strings.xml and Localizable.xcstrings
  tokens/       design tokens → generated Compose theme and SwiftUI theme
  tools/        build_presets.py, make_fixtures.py, gen_strings.py, gen_tokens.py
docs/           Product, architecture, roadmap, testing, release
web/            Static site: presets CDN, SEO exam pages, upload echo test page
.github/workflows/  android.yml, ios.yml, spec.yml (path-filtered)
```

## Commands

```bash
# spec
python3 spec/tools/build_presets.py            # validate + bundle presets (must pass before any commit touching spec/)
python3 spec/tools/gen_strings.py && python3 spec/tools/gen_tokens.py
python3 spec/tools/release_preflight.py        # what still blocks a release build (docs/RELEASE_CHECKLIST.md)

# android (from android/)
./gradlew spotlessCheck detekt testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :core:imaging:testDebugUnitTest --tests "*Conformance*"

# ios (from ios/)
xcodebuild -scheme ScanFit -destination 'platform=iOS Simulator,name=iPhone 17' test
swift test --package-path Packages/Core        # engine + conformance tests, fast (on a toolchain older than Swift 6.4 add
                                               #   --skip DesignSystemTests: the String Catalog needs the Xcode build system)
swiftlint --strict
```

## Non-negotiable rules

1. **Parity through the spec, not by copying code.** A behaviour change is made in `spec/ALGORITHMS.md`
   and `spec/fixtures/cases.json` first, then implemented on both platforms in the same PR or in
   linked PRs. Never let one platform silently diverge.
2. **Nothing the user captures leaves the device.** No image, PDF, OCR text, file name or exam
   choice goes to analytics, crash reports or any server. Analytics events carry enums and numbers only.
   Crash reports must not attach file paths containing user names.
3. **Every export is verified after writing.** Re-read the bytes from disk, re-inspect format, size,
   dimensions, DPI and colour. Show success only if the written file passes. See ALGORITHMS §1.6.
4. **Never decode a full-resolution image on the main thread or at full size unless needed.**
   Downsample on decode to at most 2× the largest target dimension (ALGORITHMS §1.1).
5. **Presets are data.** Never hard-code an exam rule in app code. If the schema can't express a rule,
   extend the schema and the validator first.
6. **Low-confidence presets show an "Unverified — check notice" badge**, and the match note never
   calls a file "valid" for them. It says "likely OK".
7. **No government emblems, logos or wording implying affiliation** anywhere, including store assets.
8. Kotlin: no `!!`, no `GlobalScope`, and state is exposed as immutable `StateFlow<UiState>`.
   Swift: Swift 6 strict concurrency, no force unwraps outside tests, and `@MainActor` view models.
9. Every new user-facing string goes in `spec/strings/en.json` **and** `hi.json`. If the Hindi
   translation is unknown, add a `"TODO_HI:"` prefix, and CI warns.
10. Add or adjust a test with every bug fix. Engine code (imaging, pdf, match, inspect) needs ≥90% line coverage.

## Definition of done (every task)

- Builds and all tests pass locally for the platform(s) touched. CI stays green.
- New UI has previews in light and dark mode, at the largest font scale and in Hindi, and a view-model test for each state.
  No screenshot tests (dropped for speed, docs/TESTING.md §1); don't add them back.
- Checked on a low-end profile: Android emulator with 2 GB RAM and API 29, iPhone SE (3rd gen) simulator.
- No new warnings. No new dependency without an entry in `docs/TECH_FEASIBILITY.md` §Dependencies.
- The ROADMAP task is ticked, with a one-line note if anything deviated.

## Things that look wrong but are intentional

- Files are sometimes *padded or upscaled* to reach a portal's **minimum** KB. Many portals set a
  minimum that a clean image at the preferred dimensions cannot reach (a 140×60 signature is about
  6 KB at q=100, but IBPS needs 10–20 KB). See ALGORITHMS §1.4. Don't "fix" this.
- Exports are always **baseline JPEG, RGB, EXIF stripped, JFIF density set**. Some portals reject
  progressive or CMYK JPEGs, and phone EXIF can carry GPS.
- Android saves to `Downloads/ScanFit/<Exam>/`, and iOS defaults to Files, not Photos. Uploading
  from Photos through some browsers may re-encode the file (see TECH_FEASIBILITY §3, spike 1).
