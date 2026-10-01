# ROADMAP.md — phased build plan

Tags: **[S]** spec · **[A]** Android · **[I]** iOS · **[W]** web · **[Ops]** store/infra.
Android leads each phase (India is Android-first); iOS follows about a week behind using the
same spec and fixtures. Each phase ends with an **exit gate**. Don't start the next phase's
feature work until the gate is green (bug fixes excepted). Weeks are relative to the start (W1).

Target: **Android public launch ~W11, iOS ~W12.** Aim to be live before the next big application
wave (typically CUET UG / NEET UG registration in Jan–Feb, and the IBPS/SSC notification cycles
through the year). Check the actual 2027 dates when scheduling the launch.

---

## Phase 0 — Foundations (W1)
- [x] [S] `spec/strings/en.json` + `hi.json` (keys namespaced `home.*`, `flow.*`, `issue.*`…), `tools/gen_strings.py` → `android/core/designsystem/src/main/res/values(-hi)/strings.xml` and `ios/.../Localizable.xcstrings`
  - *Done:* 131 keys; `gen_strings.py` writes Android `strings.xml` (+`values-hi`), `locales_config.xml`, an iOS String Catalog and typed `Strings.swift`. Validates key/placeholder parity and plurals; `--check` for CI. Hindi is all machine-drafted: 101 strings carry `TODO_HI:` (stripped from the UI, flagged `needs_review` on iOS, warned in CI) until the Phase 3 native-speaker review.
- [x] [S] `spec/tokens/tokens.json` + `tools/gen_tokens.py` → `ScanFitTheme.kt`, `Theme.swift` (colour light/dark, type, spacing, radius, motion)
  - *Done:* `gen_tokens.py` → `ScanFitTheme.kt`, `Theme.swift`. It fails the build below 4.5:1 (text) / 3:1 (graphics) in light and dark. *Deviation:* Roboto Flex is not bundled yet (APK size budget); Compose uses system Roboto.
- [x] [S] `spec/analytics/events.json` + generator → `AnalyticsEvent.kt` / `AnalyticsEvent.swift`
  - *Done:* `gen_analytics.py` → nested `AnalyticsEvent` types with no free-text parameter; Firebase name limits enforced; ints are clamped.
- [x] [S] Ed25519 test vector in `spec/fixtures/signing/` (public key, sample bundle, valid + tampered signature)
  - *Done:* `make_signing_vector.py` (deterministic): 8 verify cases + 8 loader-order cases (`bad_signature | parse | bad_schema | stale_version`). Both platforms run it, plus an independent Python cross-check. A mutation check confirmed the Android tests fail on a corrupted fixture. The dev key is derived from a public string: see `spec/signing/README.md` for creating the production key.
- [x] [A] Project: `build-logic` convention plugins, version catalog, modules per ARCHITECTURE §2 (empty), Hilt, Compose, edge-to-edge, predictive back, per-app language, R8, `dataExtractionRules`
  - *Done:* AGP 9.4.1 / Gradle 9.6.1 / Kotlin 2.4.20, 25 modules, Hilt, Compose, edge-to-edge, predictive back, per-app language (in-app picker via AppCompat), R8 (release APK 1.5 MB), `dataExtractionRules`, warnings-as-errors, spotless + detekt (2.0 alpha). *Deviations:* `compileSdk` 37 (current AndroidX needs it; `targetSdk` stays 36); placeholder `applicationId` `app.scanfit`; Roborazzi screenshot tests deferred to Phase 2 (the Phase 0 shell is throwaway UI).
- [x] [I] Project: app target + local packages per ARCHITECTURE §3, AppContainer, TabView shell, String Catalog, privacy manifest skeleton, SwiftLint/SwiftFormat
  - *Done:* XcodeGen (`ios/project.yml`), two local packages `Core` + `Features` (modules `Model`/`Data`/`Vision` are prefixed `Scan…` to avoid Apple name clashes), `AppContainer`, TabView shell, String Catalog, privacy manifest skeleton, Swift 6 strict concurrency. *Not run:* SwiftLint/SwiftFormat are configured but not installed on the dev machine.
- [x] [A][I] Embed `presets.json` + `.sig` at build time; verify the signature on load (test vector must pass, tampered must fail)
  - *Done:* Android: `EmbedPresetsTask` verifies with the JDK and refuses to embed a snapshot that does not match the key. iOS: `Scripts/embed_presets.sh` does the same. Debug embeds the dev key, release requires `prod_public_key.b64` (refused if missing). *Found and fixed:* Tink's Ed25519 cost 0.7-1.4 s per cold start on a 2 GB emulator, so Android caches a verified digest (`docs/spikes/ed25519-verify-cost.md`).
- [ ] [Ops] GitHub Actions: `spec.yml`, `android.yml`, `ios.yml` (path-filtered), caches, required checks on `main`
  - *Not done:* `spec.yml`, `android.yml`, `ios.yml` are written and parse, but have **never run**: this folder is not a git repo and has no remote. Expect first-run fixes. `swiftformat` is deliberately not a CI step.
- [ ] [W] `web/`: Cloudflare Pages (or GitHub Pages) project serving `/presets/v2/`, plus `upload-test/` (TECH_FEASIBILITY spike 1)
  - *Partly done:* `web/upload-test/` built and tested (Node + browser), `_headers`, `web/presets/v2/` staging dir. *Not done:* the Cloudflare Pages project itself (an Ops task).
- [ ] Spikes 1, 4, 5 → `docs/spikes/*.md`
  - *Not done:* see `docs/spikes/README.md`. Spike 1's tool is built; none needs the devices run yet.
- [ ] [Ops] Register the Play Console account (**organisation account if you can get a D-U-N-S number** — it skips the 12-tester rule; otherwise plan the closed test in Phase 3) and Apple Developer account; reserve the app names
  - *Not done:* needs your accounts and a D-U-N-S decision.

**Exit gate:** both empty apps build, launch, show generated theme + strings in EN/HI, load and
verify the embedded presets. CI is green on all three workflows.

> **Gate status (2026-10-01):** apps ✔ (iPhone SE 3rd gen simulator on iOS 26.5; Android emulator API 35, 2 GB; EN/HI, light/dark, verified presets shown on both).
> CI ✘ unverified (see above). DoD gaps: the Android low-end profile is API 35 not API 29 (no API 29 arm64 image installed).

## Phase 1 — Engines + conformance (W2–W3)
- [ ] [A][I] `Inspector` (ALGORITHMS §5) + all `inspect_cases`
- [ ] [A][I] `JpegPatcher` (§1.5): APP0 density insert/patch, strip APPn, COM padding, SOF assertions — unit tests with hand-built byte arrays
- [ ] [A][I] Decoder + downsample + EXIF orientation (§1.1): tests with 8 orientation fixtures (add to `make_fixtures.py`)
- [ ] [A][I] Geometry per dims mode (§1.2)
- [ ] [A][I] `FitEngine` search + minimum-size strategy (§1.3–1.4); `FitResult` report
- [ ] [A][I] `InkCleanup` (§3) signature/declaration/thumb variants + quality gate
- [ ] [A][I] `PhotoPipeline` pieces: face detect wrapper (interface + fake), auto-framing math (pure, unit-tested), segmentation wrapper, name/date strip renderer
- [ ] [A][I] `MatchEngine` (§4) pure + all `match_cases`; property tests: "a file produced by fit() for slot X always matches X as EXACT/ACCEPTED"
- [ ] [A][I] Conformance runner reading `spec/fixtures/cases.json`
- [ ] Spikes 2, 3

**Exit gate:** 100% of `cases.json` passes on both platforms in CI. Engine coverage ≥ 90%. Fit
of the photo fixture ≤ 800 ms on a mid device (benchmark test).

## Phase 2 — Core flows (A: W3–W5, I: W4–W6)
- [ ] Home: search (EN + Hindi aliases, fuzzy), categories, pinned exams, popular list
- [ ] Exam checklist screen (all states), special-rules card, source link, confidence badge, "Show unverified" setting
- [ ] Photo flow: pick/capture → face-aware crop (locked aspect) → white bg toggle → name/date toggle → review
- [ ] Ink flows: signature (incl. triple), thumb, NEET fingers (both hands), declaration (text shown from preset; disabled with "copy from notice" when `declaration_text` is null)
- [ ] [S] Transcribe `declaration_text` verbatim for IBPS PO/Clerk/RRB, SBI PO/Clerk, RBI, LIC, SEBI from the official notices; promote those presets where verified
- [ ] Review screen with `MatchNote`, near-miss Fix, before/after, verdict chips
- [ ] Export + verify-after-write (§1.6), naming (§1.7), Save all, share, "Open folder"
- [ ] My Kit (store cleaned originals, "Use for exam…", export history)
- [ ] Custom resize with the live match note
- [ ] Checker with issue fixes and per-exam verdicts
- [ ] Screenshot tests for every screen × light/dark × font 1.0/2.0 × EN/HI

**Exit gate:** a tester completes IBPS PO (4 files), JEE Main (photo, signature, class 10 PDF
placeholder) and SSC CGL (signature) end to end on a low-end Android and an iPhone SE. Every saved
file passes the upload echo page. Zero P0/P1 bugs open.

## Phase 3 — PDF, scan, guide sheet, localisation (W6–W7)
- [ ] PDF: images → PDF (+ cleanup toggle), compress to KB (§6), merge, ID card on one page
- [ ] Scan entry: ML Kit Document Scanner / VisionKit, with the Android low-RAM fallback (CameraX + 4-corner crop)
- [ ] Printable guide sheet PDF generator (true-scale boxes: signature 7:3, thumb square, declaration 2:1, triple signature, NEET fingers) + a "Print / Share" action
- [ ] Settings: language, presets version + manual update, unverified toggle, privacy, help, "Report a problem" (mailto with exam id + app/preset version, **no file**)
- [ ] Hindi review of all primary-flow strings by a native speaker; clear `TODO_HI`
- [ ] [Ops] **Start the Play closed test now** (if a personal account): ≥ 12 real testers opted in for 14 continuous days, using the app on several days. Recruit from aspirant groups/friends; give testers a script (TESTING §6)
- [ ] [Ops] TestFlight external beta group

**Exit gate:** PDF fixtures (add 3 sample PDFs to `spec/fixtures`) compress into each target;
closed test running with ≥ 12 testers.

## Phase 4 — Monetisation, coach, operations (W8)
- [ ] RevenueCat setup (products `pro_yearly`, `pro_lifetime`, entitlement `pro`), paywall screen, restore, entitlement cache offline
- [ ] Free export counter (daily, local) + rewarded ad unlock; interstitial cap; `AdGate` with kill switch; UMP consent; non-personalised requests
- [ ] Remote Config defaults + fetch; all keys in ARCHITECTURE §9 wired
- [ ] Analytics events from the generated enum; Crashlytics with no PII keys
- [ ] Preset OTA sync (WorkManager / BGTask + foreground), ETag, signature check, version monotonicity, "Spec updated" badges on exams whose files changed
- [ ] Live photo coach (§7) with thresholds from Remote Config; practice-only copy
- [ ] "Did the portal accept it?" one-tap prompt 24 h after a save (local notification only if permission was already granted; otherwise an in-app card on next open)

**Exit gate:** purchase + restore work on real devices (Play test cards/UPI test, StoreKit sandbox);
ads render and can be killed remotely; a preset change published to the CDN appears in the app within
one foreground cycle, and a tampered bundle is rejected.

## Phase 5 — Hardening + store readiness (W9–W10)
- [ ] Performance budgets (ARCHITECTURE §11) on reference devices; Baseline Profiles; memory with 48–200 MP inputs
- [ ] Accessibility audit (TalkBack, VoiceOver, 200% font, contrast)
- [ ] Device matrix run (TESTING §4); fix every P0/P1
- [ ] Store listings EN + HI: screenshots (real flows, no emblems), feature graphic, short/long description with the disclaimer + official-source statement, privacy policy URL, Data safety form, App Privacy labels, age rating questionnaires
- [ ] Legal: privacy policy + terms pages on `web/`; non-affiliation disclaimer on first run + in Settings
- [ ] Release checklist (RELEASE_AND_GTM §2) signed off

**Exit gate:** closed-test feedback addressed; crash-free ≥ 99.5% in beta; production access granted (Android).

## Phase 6 — Launch (W11–W12) and iterate
- [ ] [A] Production staged rollout: 10% → 25% (48 h, watch vitals) → 50% → 100%
- [ ] [I] App Store submission → phased release over 7 days
- [ ] [W] SEO exam pages live (RELEASE_AND_GTM §4)
- [ ] Weekly preset review: new notifications → update presets within 24 h of release

## v1.1 (W13–W18) · v1.2 (W19–W24)
See PRD §4. Each follows the same pattern: spec first → engines + conformance → flows → gate.
