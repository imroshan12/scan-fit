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
    - _Done:_ 131 keys; `gen_strings.py` writes Android `strings.xml` (+`values-hi`), `locales_config.xml`, an iOS String Catalog and typed `Strings.swift`. Validates key/placeholder parity and plurals; `--check` for CI. Hindi is all machine-drafted: 101 strings carry `TODO_HI:` (stripped from the UI, flagged `needs_review` on iOS, warned in CI) until the Phase 3 native-speaker review.
- [x] [S] `spec/tokens/tokens.json` + `tools/gen_tokens.py` → `ScanFitTheme.kt`, `Theme.swift` (colour light/dark, type, spacing, radius, motion)
    - _Done:_ `gen_tokens.py` → `ScanFitTheme.kt`, `Theme.swift`. It fails the build below 4.5:1 (text) / 3:1 (graphics) in light and dark. _Deviation:_ Roboto Flex is not bundled yet (APK size budget); Compose uses system Roboto.
- [x] [S] `spec/analytics/events.json` + generator → `AnalyticsEvent.kt` / `AnalyticsEvent.swift`
    - _Done:_ `gen_analytics.py` → nested `AnalyticsEvent` types with no free-text parameter; Firebase name limits enforced; ints are clamped.
- [x] [S] Ed25519 test vector in `spec/fixtures/signing/` (public key, sample bundle, valid + tampered signature)
    - _Done:_ `make_signing_vector.py` (deterministic): 8 verify cases + 8 loader-order cases (`bad_signature | parse | bad_schema | stale_version`). Both platforms run it, plus an independent Python cross-check. A mutation check confirmed the Android tests fail on a corrupted fixture. The dev key is derived from a public string: see `spec/signing/README.md` for creating the production key.
- [x] [A] Project: `build-logic` convention plugins, version catalog, modules per ARCHITECTURE §2 (empty), Hilt, Compose, edge-to-edge, predictive back, per-app language, R8, `dataExtractionRules`
    - _Done:_ AGP 9.4.1 / Gradle 9.6.1 / Kotlin 2.4.20, 25 modules, Hilt, Compose, edge-to-edge, predictive back, per-app language (in-app picker via AppCompat), R8 (release APK 1.5 MB), `dataExtractionRules`, warnings-as-errors, spotless + detekt (2.0 alpha). _Deviations:_ `compileSdk` 37 (current AndroidX needs it; `targetSdk` stays 36); placeholder `applicationId` `app.scanfit`; Roborazzi screenshot tests deferred to Phase 2 (the Phase 0 shell is throwaway UI), later dropped.
- [x] [I] Project: app target + local packages per ARCHITECTURE §3, AppContainer, TabView shell, String Catalog, privacy manifest skeleton, SwiftLint/SwiftFormat
    - _Done:_ XcodeGen (`ios/project.yml`), two local packages `Core` + `Features` (modules `Model`/`Data`/`Vision` are prefixed `Scan…` to avoid Apple name clashes), `AppContainer`, TabView shell, String Catalog, privacy manifest skeleton, Swift 6 strict concurrency. _Not run:_ SwiftLint/SwiftFormat are configured but not installed on the dev machine.
- [x] [A][I] Embed `presets.json` + `.sig` at build time; verify the signature on load (test vector must pass, tampered must fail)
    - _Done:_ Android: `EmbedPresetsTask` verifies with the JDK and refuses to embed a snapshot that does not match the key. iOS: `Scripts/embed_presets.sh` does the same. Debug embeds the dev key, release requires `prod_public_key.b64` (refused if missing). _Found and fixed:_ Tink's Ed25519 cost 0.7-1.4 s per cold start on a 2 GB emulator, so Android caches a verified digest (`docs/spikes/ed25519-verify-cost.md`).
- [ ] [Ops] GitHub Actions: `spec.yml`, `android.yml`, `ios.yml` (path-filtered), caches, required checks on `main`
    - _Not done:_ `spec.yml`, `android.yml`, `ios.yml` are written and parse, but have **never run**: this folder is not a git repo and has no remote. Expect first-run fixes. `swiftformat` is deliberately not a CI step.
- [ ] [W] `web/`: Cloudflare Pages (or GitHub Pages) project serving `/presets/v2/`, plus `upload-test/` (TECH_FEASIBILITY spike 1)
    - _Partly done:_ `web/upload-test/` built and tested (Node + browser), `_headers`, `web/presets/v2/` staging dir. _Not done:_ the Cloudflare Pages project itself (an Ops task).
- [ ] Spikes 1, 4, 5 → `docs/spikes/*.md`
    - _Not done:_ see `docs/spikes/README.md`. Spike 1's tool is built; none needs the devices run yet.
- [ ] [Ops] Register the Play Console account (**organisation account if you can get a D-U-N-S number** — it skips the 12-tester rule; otherwise plan the closed test in Phase 3) and Apple Developer account; reserve the app names
    - _Not done:_ owner handles this (both store accounts already exist); not tracked by engineering.

**Exit gate:** both empty apps build, launch, show generated theme + strings in EN/HI, load and
verify the embedded presets. CI is green on all three workflows.

> **Gate status (2026-10-01):** apps ✔ (iPhone SE 3rd gen simulator on iOS 26.5; Android emulator API 35, 2 GB; EN/HI, light/dark, verified presets shown on both).
> CI ✘ unverified (see above). DoD gaps: the Android low-end profile is API 35 not API 29 (no API 29 arm64 image installed).

## Phase 1 — Engines + conformance (W2–W3)

> Spec first (CLAUDE.md rule 1): `ALGORITHMS.md` §9 pins every number both platforms must agree on, and `cases.json` grew from 15 to ~70
> cases (new sections `decode_cases`, `patch_cases`, `geometry_cases`; 13 new fixtures incl. the 8 EXIF orientations). Writing §9 and the second
> platform found real gaps in the original text, all resolved in the spec: the q range (§1.3 vs §1.4), the pad target, `range`-mode start size, windows
> narrower than 2 KB, sRGB ICC detection in UTF-16 (v4 profiles), the box-blur formula, morphology borders, and how a crop maps onto a downsampled image.

- [x] [A][I] `Inspector` (ALGORITHMS §5) + all `inspect_cases`
    - _Done:_ 16 cases + hostile/truncated-input tests on both platforms. Swift reads through bounds-safe accessors (it traps where the JVM throws).
- [x] [A][I] `JpegPatcher` (§1.5): APP0 density insert/patch, strip APPn, COM padding, SOF assertions — unit tests with hand-built byte arrays
    - _Done:_ 10 shared `patch_cases` + byte-array tests; padding hits the exact byte target for gaps of 4 B to 200 KB.
- [x] [A][I] Decoder + downsample + EXIF orientation (§1.1): tests with 8 orientation fixtures (add to `make_fixtures.py`)
    - _Done:_ the 8 fixtures are self-verified by the generator; production decoders (BitmapFactory + `Orientation`, ImageIO) pass all 10 `decode_cases`.
- [x] [A][I] Geometry per dims mode (§1.2)
    - _Done:_ pure functions, 8 shared `geometry_cases` re-derived independently in Python (`test_tools.py`).
- [x] [A][I] `FitEngine` search + minimum-size strategy (§1.3–1.4); `FitResult` report
    - _Done:_ all paths covered (search, downscale, upscale, upscale-then-pad, pad, `pad_only`, exact mode, range floors, typed errors).
- [x] [A][I] `InkCleanup` (§3) signature/declaration/thumb variants + quality gate
    - _Done:_ signature/document/thumb + coverage gate; real paper-shadow and thumb fixtures clean up on both platforms.
- [x] [A][I] `PhotoPipeline` pieces: face detect wrapper (interface + fake), auto-framing math (pure, unit-tested), segmentation wrapper, name/date strip renderer
    - _Done:_ interfaces + fakes (`core:vision`/`ScanVision`, fakes in `core:testing`/`TestSupport`), `AutoFraming`, `BackgroundWhitening`, strip layout + real text rendering (Skia / CoreText). _Not done:_ the ML Kit / Vision implementations (Phase 2, behind the same interfaces).
- [x] [A][I] `MatchEngine` (§4) pure + all `match_cases`; property tests: "a file produced by fit() for slot X always matches X as EXACT/ACCEPTED"
    - _Done:_ 16 cases against the real presets; the property runs over every distinct real slot × 4 sources on both platforms (with a non-vacuity guard).
- [x] [A][I] Conformance runner reading `spec/fixtures/cases.json`
    - _Done:_ `fit_cases` run end to end through the production codecs (Skia under Robolectric native graphics; ImageIO). Mutation checks confirmed each runner fails when behaviour changes.
- [ ] Spikes 2, 3
    - _Not done:_ spike 2 needs real portal validators, spike 3 needs consented real photos. Spike 5 got its answer for the JVM half: Robolectric native graphics gives real Skia JPEG encoding (`docs/spikes/05-robolectric-native-graphics.md`); the ±10% comparison with a device is still open.

**Exit gate:** 100% of `cases.json` passes on both platforms in CI. Engine coverage ≥ 90%. Fit
of the photo fixture ≤ 800 ms on a mid device (benchmark test).

> **Gate status (2026-10-01), local runs only:**
>
> - `cases.json`: every section passes on both platforms (Android 160 unit tests, 0 failures; Swift Core + Features + the app target on iPhone 17).
> - Engine line coverage (rule 10, ≥ 90%), measured: Android `inspect` 95.1%, `match` 98.4%, `imaging` 98.8% (JaCoCo, `android/config/coverage/check_coverage.py`);
>   iOS `Inspect` 96.2%, `Imaging` 97.8%, `Match` 96.4% (`ios/Scripts/check_coverage.py`). Both scripts fail on a missing report or zero measured lines.
> - Fit benchmark (`ibps_photo_from_phone`, budget 800 ms): Android 138 ms median (Robolectric on the build machine), Swift 48 ms (release build, same machine).
>   These guard against regressions. They are **not** mid-device numbers; the on-device figure needs the Macrobenchmark/XCTest-on-device run (Phase 2, with a real flow to drive).
> - CI ✘ unverified: `android.yml` (coverage step now enabled), `ios.yml` and `spec.yml` have never run, so "in CI" is not yet true.

## Phase 2 — Core flows (A: W3–W5, I: W4–W6)

> **Status (2026-10-06):** Home, photo flow and ink flows built on both apps; export (verify-after-write, Saved checklist rows) is
> shared by both flows from the core layer; reviews show the match note, Before/After comparison and technical verdict chips.
> Photo/ink now retain verified final drafts for 30 days; Ready rows survive relaunch and reopen read-only reviews.
> Device/provider QA remains open. Details and decisions: docs/HANDOFF.md.

- [x] Home: search (EN + Hindi aliases, fuzzy), categories, pinned exams, popular list
    - _Done (both apps):_ ALGORITHMS §10 search with `aliases` (all 55 presets), `categories.json` and `popular.json` in the signed bundle
      (presets_version 2); 25 `search_cases` computed by a Python reference that Kotlin and Swift both pass (mutation-checked). Home: live search,
      category chips, My exams (pinned, DataStore / UserDefaults) and Popular now; "no matches" is never shown while specs are still verifying.
      Checked by hand on an API 35 emulator and the iPhone 17 simulator.
- [ ] Exam checklist screen (all states), special-rules card, source link, confidence badge, "Show unverified" setting
    - _Done:_ header with `ConfidenceBadge` (unverified = "Unverified — check notice"), verified date + source link, document rows with
      `SpecSummary` (`20–50 KB · 200×230`), "Before you upload" card, pin, not-found state; Settings toggle ("Show unverified exams",
      **on by default** since 2026-10-04: unverified exams are listed with their badge). Photo rows open the photo flow (chevron).
      _Added:_ Saved row status persists after verified photo/ink exports. Verified private drafts show Ready · KB after
      relaunch; Saved takes precedence. Entry/foreground/store changes revalidate availability against current presets.
      _Open:_ broader PDF/live-photo flows and device QA.
        - [x] [S][A][I] Retained drafts + intermediate Ready rows (2026-10-06): ALGORITHMS §1.6.1, all 11 `draft_cases`, native
              atomic storage and relaunch/restore/failed-replacement tests. Tapping Ready reopens identical final bytes for Save;
              Replace file starts a new source without deleting the last good draft. No new DB/dependency or editable originals.
- [ ] Photo flow: pick/capture → face-aware crop (locked aspect) → white bg toggle → name/date toggle → review
    - _Done (both apps):_ ALGORITHMS §9.6 gained the manual-adjust rules (move/zoom/rotate), the step order and the segmenter's 0.5
      threshold; 12 `crop_cases` from a Python reference (`spec/tools/photo_crop.py`) pass on both. ML wrappers: ML Kit (bundled face
      detection + selfie segmentation) and Vision (CPU on the simulator, which has no inference context). Flow: exam row → pick (Photo
      Picker / PhotosPicker, or the system camera) → face check → aspect-locked crop → white background, name/date strip → fit →
      review with the re-inspected size and the slot verdict ("Likely OK · Unverified" for low-confidence presets). View-model tests:
      18 Android, 16 iOS, both mutation-checked.
    - _Added after review with the user (2026-10-04):_ a third source, **Choose from Files** (`OpenDocument` / `fileImporter`); the
      name/date strip is **off by default** (a hint shows when the preset asks for it, ALGORITHMS §2.4); iOS leaves the flow through an
      explicit navigation path (`PhotoFlowView(onExit:)`) instead of `dismiss()`; Android opens the flow `launchSingleTop`.
    - _Deviations:_ the camera is the system camera app for now; the in-app camera with the aspect overlay and tips (UI_UX §3) comes
      with the live coach (CameraX / AVFoundation, Phase 3). Review now saves to Downloads/Files, then offers Done after verification.
    - _Checked by hand:_ iPhone 11 Pro Max simulator (iOS 26.5): exam row → pick → decode → "No face found" on a photo without a
      face; with a stub face detector: crop → review (36 KB · 200×230, "Meets the IBPS PO / MT rules") → Done back to the exam, from
      Popular and from search; the Files picker opens. _Open:_ "Done does nothing" reported on the user's physical device (fix applied,
      unconfirmed — HANDOFF §3); crop/review with a real face on both apps; the Android emulator froze before the flow could be
      walked; no iOS 17 simulator runtime or API 29 image is installed.
- [x] Ink flows: signature (incl. triple), thumb, NEET fingers (both hands), declaration (text shown from preset; disabled with "copy from notice" when `declaration_text` is null)
    - _Done (both apps, 2026-10-05):_ ALGORITHMS §3 "Flow" and §9.5 (doc type → cleanup variant / match kind, review options, free
      crop `resize`, one-time handwriting confirmation); 6 new `crop_cases` (`resize`) from `photo_crop.py`. Flow: exam row → pick
      (camera / gallery / Files) → free crop with corner handles → cleanup → pad + fit → review (Crisp black, Darker ink, too faint /
      too dark warning) → save as the slot's kind → Saved. Export moved to the core layer (`:core:data` `DocumentExporter`,
      `ScanData` `ExportOperation` / `FilesExporter`) and is shared with the photo flow. View-model tests: 16 Android, 14 iOS, both
      mutation-checked. Checked by hand on the iPhone 11 Pro Max simulator: IBPS signature saved to Files, the written file
      re-inspected (12 KB, 273×117, SOF0, 3 components, JFIF 200 dpi, no EXIF), row shows Saved.
    - _Deviations:_ rectification (4-corner warp) comes with the document scanner; the crop is an axis-aligned rectangle. A
      declaration with no `declaration_text` still opens (it shows "Copy the text from the official notice") rather than being
      disabled. _Open:_ Android hand check; cleanup speed on a real low-end device (≈20 s in a simulator debug build).
- [ ] [S] Transcribe `declaration_text` verbatim for IBPS PO/Clerk/RRB, SBI PO/Clerk, RBI, LIC, SEBI from the official notices; promote those presets where verified
- [x] Review screen with `MatchNote`, near-miss Fix, before/after, verdict chips
    - _Done (both apps, 2026-10-05): the match note._ ALGORITHMS §4 "Match note on review" and §9.7 "Match note" (headline,
      first three names, likely-OK count, quick fixes with their need, sheet grouped by body); 6 `match_note_cases` computed by
      `spec/tools/match_note.py`, passed by Kotlin and Swift `MatchNote.of` (mutation-checked). `MatchEntry` carries the slot's
      `size_kb`. `MatchNoteCard` + detail sheet in the design system on both apps; photo and ink reviews show it on every render.
      Checked by hand on the iPhone 11 Pro Max simulator (IBPS signature: "Accepted by 32 exams", 4 quick fixes, sheet by body).
    - _Deviation:_ no Fix button in exam flows: the file is made for that exam's slot, so near misses only say what the other exam
      needs ("Needs ≥ 20 KB"); Fix comes with the Checker and Custom resize.
        - _Done (both apps, 2026-10-06):_ shared Before/After with hold-to-compare, bounded pre-effect crop, fixed 280 dp/pt
          letterboxed preview and off-thread conversion/decoding. Three accessible KB/dimensions/JPG chips use the output's
          `SlotEvaluation`; all 8 `review_check_cases` pass on both apps. Comparison leaves rendering, Saved and export bytes
          unchanged; pending edits/failures hide stale results. Light/dark/Hindi/largest-font previews and focused flow tests pass.
          _Open QA:_ real-device gestures, TalkBack/VoiceOver and minimum-runtime checks; no new dependencies or screenshot tests.
- [ ] Export + verify-after-write (§1.6), naming (§1.7), Save all, share, "Open folder"
    - [x] [S][A][I] Single-photo save + destination verification + persistent Saved rows (2026-10-04).
          _Done:_ pending MediaStore on API 29+, CreateDocument below; iOS Files export picker with coordinated security-scoped re-read.
          Bytes must match Review and pass format, encoding, dimensions, KB, DPI and privacy metadata checks. Cancellation is silent;
          failures retry, cleanup is best effort, duplicate taps and stale renders are guarded. Eight shared export cases on both apps.
          _Deviation:_ checklist status uses existing preferences, not an export-history DB; iOS folder is user-selected.
          _Open:_ device/provider/echo-page QA, Save all, sharing, Open folder and export history.
    - [x] [I] Fix missing Saved after Files export (2026-10-04): sheet dismissal no longer cancels the export before its
          delegate result. Callback-order and Files-to-checklist persistence regressions pass; device confirmation remains open.
        - [x] [S][A][I] Private retained final drafts (2026-10-06): one verified JPEG per exam/slot, 30-day validity,
              backup excluded, bounded structured records with opaque names and atomic replacement. Reads use current slot
              verification; stale/corrupt/expired/retired files cannot show Ready. Restored reviews are read-only and export the
              same bytes, retaining handwriting confirmation. Failure warns without disabling immediate export or losing the
              previous good draft. _Open:_ physical-device QA; cleaned originals and export history come with My Kit.
- [ ] My Kit (store cleaned originals, "Use for exam…", export history)
- [ ] Custom resize with the live match note
- [ ] Checker with issue fixes and per-exam verdicts
- [x] ~~Screenshot tests for every screen × light/dark × font 1.0/2.0 × EN/HI~~ — dropped (2026-10-03, product decision)
    - Built on both apps (Roborazzi, swift-snapshot-testing), then removed: baselines made every UI change slow. Screens are checked
      with previews (light/dark/largest font/Hindi), by hand on the low-end profiles and by view-model tests (docs/TESTING.md §1).
      The one bug they found (English month names on the Hindi exam screen) is fixed on both apps and covered by unit tests.

**Exit gate:** a tester completes IBPS PO (4 files), JEE Main (photo, signature, class 10 PDF
placeholder) and SSC CGL (signature) end to end on a low-end Android (4 GB) and an iPhone 11 Pro Max on iOS 17. Every saved
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

- [x] [A][I] Release build plumbing (pulled forward on request): signed AAB/APK with an upload key from env or `keystore.properties`, version from tag and run number, dev presets key refused in release, iOS Release archive/export script, `release_preflight.py`, tag-driven CI release jobs, PR release rehearsal. See `docs/RELEASE_CHECKLIST.md`
    - _Done:_ verified locally with throwaway keys (R8 APK runs on an API 35 emulator; unsigned iOS Release archive). _Not verified:_ signed iOS export, TestFlight upload, the CI release jobs (need your credentials and a tag). Placeholders you replace are listed in the checklist.
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
