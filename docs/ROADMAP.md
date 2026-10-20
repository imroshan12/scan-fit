# ROADMAP.md — phased build plan

Tags: **[S]** spec · **[A]** Android · **[I]** iOS · **[W]** web · **[Ops]** store/infra.
Android leads each phase (India is Android-first); iOS follows about a week behind using the
same spec and fixtures. Each phase ends with an **exit gate**. Don't start the next phase's
feature work until the gate is green (bug fixes excepted). Weeks are relative to the start (W1).

Target: **Android public launch ~W11, iOS ~W12.** Aim to be live before the next big application
wave (typically CUET UG / NEET UG registration in Jan–Feb, and the IBPS/SSC notification cycles
through the year). Check the actual 2027 dates when scheduling the launch.

**Update 2026-10-20 (user decisions):** store name **Mr Doc**; a lean v1.0 (what is built + Pro + Settings + legal +
Hindi review) launches on both stores in the **first week of December 2026**. Scan entry, the guide sheet and the live
coach move to v1.1. Monetisation is 20 free saves then Pro, no ads (PRD §6). Week-by-week plan: `docs/LAUNCH_PLAN.md`.

---

## Phase 0 — Foundations (W1)

- [x] [S] `spec/strings/en.json` + `hi.json` (keys namespaced `home.*`, `flow.*`, `issue.*`…), `tools/gen_strings.py` → `android/core/designsystem/src/main/res/values(-hi)/strings.xml` and `ios/.../Localizable.xcstrings`
    - _Done:_ 131 keys; `gen_strings.py` writes Android `strings.xml` (+`values-hi`), `locales_config.xml`, an iOS String Catalog and typed `Strings.swift`. Validates key/placeholder parity and plurals; `--check` for CI. Hindi is all machine-drafted: 101 strings carry `TODO_HI:` (stripped from the UI, flagged `needs_review` on iOS, warned in CI) until the Phase 3 native-speaker review.
- [x] [S] `spec/tokens/tokens.json` + `tools/gen_tokens.py` → `ScanFitTheme.kt`, `Theme.swift` (colour light/dark, type, spacing, radius, motion)
    - _Done:_ `gen_tokens.py` → `ScanFitTheme.kt`, `Theme.swift`. It fails the build below 4.5:1 (text) / 3:1 (graphics) in light and dark. _Deviation:_ Roboto Flex is not bundled yet (APK size budget); Compose uses system Roboto.
- [x] [S] `spec/analytics/events.json` + generator → `AnalyticsEvent.kt` / `AnalyticsEvent.swift`
    - _Done:_ `gen_analytics.py` → nested `AnalyticsEvent` types with no free-text parameter; Firebase name limits enforced; ints are clamped.
- [x] [S] Ed25519 test vector in `spec/fixtures/signing/` (public key, sample bundle, valid + tampered signature). _Superseded 2026-10-20: presets are no longer signed (user decision); the loader cases live on as `spec/fixtures/presets_load/` (format checks only)._
    - _Done:_ `make_signing_vector.py` (deterministic): 8 verify cases + 8 loader-order cases (`bad_signature | parse | bad_schema | stale_version`). Both platforms run it, plus an independent Python cross-check. A mutation check confirmed the Android tests fail on a corrupted fixture. The dev key is derived from a public string: see `spec/signing/README.md` for creating the production key.
- [x] [A] Project: `build-logic` convention plugins, version catalog, modules per ARCHITECTURE §2 (empty), Hilt, Compose, edge-to-edge, predictive back, per-app language, R8, `dataExtractionRules`
    - _Done:_ AGP 9.4.1 / Gradle 9.6.1 / Kotlin 2.4.20, 25 modules, Hilt, Compose, edge-to-edge, predictive back, per-app language (in-app picker via AppCompat), R8 (release APK 1.5 MB), `dataExtractionRules`, warnings-as-errors, spotless + detekt (2.0 alpha). _Deviations:_ `compileSdk` 37 (current AndroidX needs it; `targetSdk` stays 36); placeholder `applicationId` `app.scanfit`; Roborazzi screenshot tests deferred to Phase 2 (the Phase 0 shell is throwaway UI), later dropped.
- [x] [I] Project: app target + local packages per ARCHITECTURE §3, AppContainer, TabView shell, String Catalog, privacy manifest skeleton, SwiftLint/SwiftFormat
    - _Done:_ XcodeGen (`ios/project.yml`), two local packages `Core` + `Features` (modules `Model`/`Data`/`Vision` are prefixed `Scan…` to avoid Apple name clashes), `AppContainer`, TabView shell, String Catalog, privacy manifest skeleton, Swift 6 strict concurrency. _Not run:_ SwiftLint/SwiftFormat are configured but not installed on the dev machine.
- [x] [A][I] Embed `presets.json` + `.sig` at build time; verify the signature on load (test vector must pass, tampered must fail). _Changed 2026-10-20: only `presets.json` is embedded; the apps run format checks, Tink and the digest cache are gone._
    - _Done:_ Android: `EmbedPresetsTask` verifies with the JDK and refuses to embed a snapshot that does not match the key. iOS: `Scripts/embed_presets.sh` does the same. Debug embeds the dev key, release requires `prod_public_key.b64` (refused if missing). _Found and fixed:_ Tink's Ed25519 cost 0.7-1.4 s per cold start on a 2 GB emulator, so Android caches a verified digest (`docs/spikes/ed25519-verify-cost.md`).
- [ ] [Ops] GitHub Actions: `spec.yml`, `android.yml`, `ios.yml` (path-filtered), caches, required checks on `main`
    - _Not done:_ `spec.yml`, `android.yml`, `ios.yml` are written and parse, but have **never run**: this folder is not a git repo and has no remote. Expect first-run fixes. `swiftformat` is deliberately not a CI step.
- [ ] [W] `web/`: Cloudflare Pages (or GitHub Pages) project serving `/presets/v2/`, plus `upload-test/` (TECH_FEASIBILITY spike 1). _2026-10-20: GitHub Pages chosen (user); `.github/workflows/pages.yml` deploys `web/` to `https://imroshan12.github.io/scan-fit/` once Pages is set to "GitHub Actions" in the repo settings._
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

> **Status (2026-10-13):** Home, photo flow and ink flows built on both apps; export (verify-after-write, Saved checklist rows) is
> shared by both flows from the core layer; reviews show the match note, Before/After comparison and technical verdict chips.
> Photo/ink now retain verified final drafts for 30 days; Ready rows survive relaunch and reopen read-only reviews.
> 2026-10-13: Custom size (any px/cm/KB) runs the same flows; thumb cleanup no longer whitens heavy ink or clips the print.
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
- [x] Export + verify-after-write (§1.6), naming (§1.7), Save all, share, "Open folder"
    - [x] [S][A][I] Share and Save all (2026-10-13): ALGORITHMS §1.6.2; 6 `save_all_cases` from `spec/tools/save_all.py`.
          Share stages exactly the review bytes (re-read, byte-equal) under the §9.8 name in a private cache folder emptied per
          share and on app start, same gate as Save, never records Saved. Save all saves every Ready row through the full
          verified export, sequentially, failures isolated, retry re-plans; exam signatures without the tick are held. iOS: one
          Files picker for all files, returned URLs matched to requests by bytes (survives "Keep both" renames, never touches an
          unmatched file). Android 10+: MediaStore with progress, then Open folder (system Downloads view).
          _Deviations:_ no Save all on Android 8–9 (one picker per file); Open folder cannot target the subfolder; iOS shows no
          per-file progress (one picker). Export history is still open (My Kit).
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
- [x] [S][A][I] My Kit (store cleaned originals, "Use for exam…", export history) (2026-10-15, ALGORITHMS §13): three
      views (Prepared · Originals · History). Save to My Kit on new photo/ink reviews keeps the upright working picture
      (≤ 2400 px, q92, metadata stripped), up to 30, until deleted. "Use for exam…" opens the Checker's list and starts
      the slot's flow at the crop with the original. Every verified export (review, Save all, Checker, Custom) is listed,
      newest 100, with a private copy for 30-day re-share; Clear history. `kit_size/history/id_cases` from `spec/tools/kit.py`.
      _Deviation:_ a JSON index + blob files instead of Room/SwiftData (ARCHITECTURE §3); "cleaned" means oriented,
      downsampled and metadata-free, not ink cleanup (each slot cleans its own crop). _Open:_ device QA; Pro-only
      unlimited retention and "Make all files" from My Kit come with Phase 4.
  - [x] [A][I] Prepared-file browser (2026-10-06, user-reported empty Kit): lists verified retained photo/ink files by
      active exam, including hidden unverified exams with their badge. Re-entry/foreground/store revisions refresh the
      list; files reopen the existing restored review and Done returns to Kit. Tests cover actual file relaunch,
      invalid/expired/current-preset removal, empty/error/retry, ordering and stale loads. _Open:_ reusable cleaned
      originals, "Use for exam…", export history and device navigation/accessibility QA; current files retain the 30-day policy.
- [x] [S][A][I] Signature ink-loss fix (2026-10-06, reported for UPSC ESE on iOS): signature/triple-signature cleanup
      no longer erodes strokes or deletes small connected components. All 3 `ink_preservation_cases` and native UPSC ESE
      fit/encode regressions pass. Documents keep their filters; thumbs unchanged. _Open:_ confirmation with the user's
      source image; previously damaged retained files must be replaced from the original.
- [x] Custom size (PRD F9) with the match note
    - _Done (both apps, 2026-10-13):_ ALGORITHMS §11 (validation codes, px/cm@dpi, synthetic slot, canonical text, recent sizes);
      18 `custom_size_cases` from `spec/tools/custom_size.py`, passed by Kotlin and Swift. A `FlowTarget` (exam slot or Custom
      size) feeds the **same** photo/ink flows: no draft, no Saved status, no handwriting tick, "Meets your size". Form in
      `:feature:custom` / `Custom` (doc kind, px/cm, DPI, min/max KB, recent sizes, keyboard Next/Done); entry from Tools and
      Home. VM tests: Custom form 6 + 6, custom flows 6 Android + 5 iOS. Checked by hand on the iPhone 11 Pro Max simulator.
    - _Deviations:_ JPG only (no format choice), no aspect lock or live size estimate before capture (the review shows the
      real size). Near-miss Fix is still for the Checker.
- [x] Checker with issue fixes and per-exam verdicts
    - _Done (both apps, 2026-10-15):_ ALGORITHMS §12; 10 `checker_cases` from `spec/tools/checker.py`, passed by Kotlin and
      Swift (`CheckerStatus`, `CheckerTargets` in `core:match` / `Match`). Pick from gallery or Files (image or PDF) → the file's
      own problems → the user says what it is (chips) → match note + exam list (popular first, search) → per-slot verdict with
      every issue in numbers → "Make it fit" refits a copy to that slot, re-inspected and verified on save; Save records Saved
      for that exam, Share never does; exam signatures need the handwriting tick. Entry from Tools and Home. VM tests: 7
      Android + 6 iOS. Checked by hand on the iPhone 11 Pro Max simulator.
    - _Deviations:_ one Fix (refit the whole copy to the chosen slot) instead of per-issue actions; no PDF fixes (PDF slots
      said "PDF tools are coming soon"; since 2026-10-20 "Make it fit" makes a PDF, ALGORITHMS §12 rule 4); the kind is chosen by the user, never guessed; the refit uses the plain fit pipeline
      (no ink cleanup — the ink flow does that); no `checker_run` analytics event yet.
- [x] ~~Screenshot tests for every screen × light/dark × font 1.0/2.0 × EN/HI~~ — dropped (2026-10-03, product decision)
    - Built on both apps (Roborazzi, swift-snapshot-testing), then removed: baselines made every UI change slow. Screens are checked
      with previews (light/dark/largest font/Hindi), by hand on the low-end profiles and by view-model tests (docs/TESTING.md §1).
      The one bug they found (English month names on the Hindi exam screen) is fixed on both apps and covered by unit tests.

**Exit gate:** a tester completes IBPS PO (4 files), JEE Main (photo, signature, class 10 PDF
placeholder) and SSC CGL (signature) end to end on a low-end Android (4 GB) and an iPhone 11 Pro Max on iOS 17. Every saved
file passes the upload echo page. Zero P0/P1 bugs open.

## Phase 3 — PDF, scan, guide sheet, localisation (W6–W7)

- [x] PDF: images → PDF (+ cleanup toggle), compress to KB (§6), merge, ID card on one page
    - Built on both apps (2026-10-15/20): ALGORITHMS §6 + §9.10, `spec/tools/pdf.py`, 4 sample PDFs in `spec/fixtures/pdfs/`,
      `pdf_layout/writer/fit/job/name_cases` + `page_cleanup_cases`. Android `:core:pdf` + `:feature:pdf`, iOS `PDF` + `PDFTools`.
      Exam PDF rows open the PDF maker (records Saved); the Checker's "Make it fit" makes a PDF for PDF slots; PDF exports
      join My Kit History.
    - _Deviations:_ the app writes its own image PDF (no `PdfDocument`/PDFKit page copy) so size is exact and both apps match
      byte for byte; a fixed quality grid instead of a binary search (one page raster in memory at a time), extended to
      q20 at 100 DPI because iOS ImageIO compresses less; a Tools limit never pads; merge/compress rasterise pages (text
      not selectable, user decision); grayscale + cleanup on by default (user decision). Pro limits come with Phase 4.
      Android's real `PdfRenderer` is device-QA only (Robolectric cannot run it).
- [x] Editor-first Home + My Kit previews (user request, 2026-10-20; not in the original plan)
    - Home is a grid of large tool cards (Exam forms is one card; the old list moved to Exam forms); tabs Home · My Kit ·
      Settings (Tools tab removed, its tools are cards). My Kit: row thumbnails, History file preview (image / PDF pages),
      Save to My Kit shows the picture first. UI_UX §1–3, ALGORITHMS §6.6, §11, §13.3. Device QA open (HANDOFF §5).
- [ ] Scan entry: ML Kit Document Scanner / VisionKit, with the Android low-RAM fallback (CameraX + 4-corner crop). _Moved to v1.1 (lean v1.0, 2026-10-20)._
- [ ] Printable guide sheet PDF generator (true-scale boxes: signature 7:3, thumb square, declaration 2:1, triple signature, NEET fingers) + a "Print / Share" action. _Moved to v1.1 (lean v1.0, 2026-10-20)._
- [x] Settings: language, presets version + manual update, unverified toggle, privacy, help, "Report a problem" (mailto with exam id + app/preset version, **no file**); Pro status, free saves left and Restore purchases come with Phase 4
      _Done 2026-10-20 on both apps:_ Pro (status, free saves left, Get Pro, Restore), language, unverified, presets version, privacy (note, analytics opt-out, policy, terms), Help, Report a problem (mailto: app, OS and specs versions, no file, no exam id from Settings), disclaimer, app version. _Deviation:_ no manual presets update button until OTA sync exists (Phase 4); website links are placeholders on `mrdoc.example` (`AppLinks`, preflight blocks `--final`). Account rows come with sign-in.
- [ ] Rename to **Mr Doc** (user decision 2026-10-20): app display name, user-facing strings that say "ScanFit", website. Module and package names stay. Pick the permanent `applicationId` / bundle id before the first upload (RELEASE_CHECKLIST §1)
      _Done 2026-10-20 except the ids:_ `app.name`, every "ScanFit" string (en + hi), iOS `CFBundleDisplayName`/`CFBundleName` now spec keys (`infoplist.display_name`, `infoplist.bundle_name`), camera text, the Home wordmark, `web/`, and the Android folder `Downloads/Mr Doc/<Exam>/` (user choice; `ExportNaming.APP_FOLDER`). Waiting on the user's permanent ids.
- [ ] Hindi review of all primary-flow strings by a native speaker; clear `TODO_HI`
- [ ] [Ops] **Start the Play closed test now — target 2026-10-27** (the account is personal, so this is mandatory): ≥ 12 real testers opted in for 14 continuous days, using the app on several days. Recruit from aspirant groups/friends; give testers a script (TESTING §6)
- [ ] [Ops] TestFlight external beta group

**Exit gate:** PDF fixtures (add 3 sample PDFs to `spec/fixtures`) compress into each target;
closed test running with ≥ 12 testers.

## Phase 4 — Monetisation, coach, operations (W8)

- [ ] RevenueCat setup (products `pro_yearly`, `pro_lifetime`, entitlement `pro`), paywall screen, restore, entitlement cache offline
      _Partly done 2026-10-20:_ `Billing` interface on both apps (`:core:billing`, iOS `Billing`) with `LocalBilling` (release: store unavailable; debug: fake ₹99/₹199 products granting Pro for the session); paywall screen + VM on both apps (ALGORITHMS 14.3: loading, unavailable/Retry, buying, pending, failed, thanks, Restore, privacy/terms links). Open: RevenueCat SDK behind `Billing` (needs the user's RevenueCat project and keys), `logIn(uid)`, offline entitlement cache.
      _QA follow-up 2026-10-20:_ fixed stale store responses across close/reopen on both apps; 3 shared lifecycle cases plus duplicate-action/offer guards. iOS `ScanFitSmoke` (4 XCUITests, no screenshot baselines) passes on iPhone 11 Pro Max / iOS 26.5: Settings, fake purchase, Restore, unavailable-store Retry, Hindi largest text. Real purchases, Android native UI and iOS 17 remain open.
- [ ] Accounts (user decision 2026-10-20, PRD §6): sign-in on first launch (Google; Apple on iPhone), Firebase Auth + database record with the save count only, Sign out / Delete account in Settings, security rules, App Check, offline first launch
- [x] Free saves (user decision 2026-10-20, PRD §6): 20 saves in total, then the paywall; `SaveQuota` (ARCHITECTURE §8), "N free saves left" in Settings and on the review at ≤ 5. Spec the counting rules in ALGORITHMS first. **No ads** (the old rewarded/interstitial/UMP task is dropped)
      _Done 2026-10-20 on both apps, local only:_ ALGORITHMS §14 + 11 `save_quota_cases` (reference `spec/tools/quota.py`); `SaveQuotaRules`, `FileQuotaStore` (no-backup), `SaveGate` gating review Save/Share, Checker, PDF tools/slots, Custom size and Save all (whole batch decided first); History re-share and Save to My Kit free. Open: the account sync (`merge`) with sign-in, `free_saves_total` from Remote Config, `free_saves_spent` analytics.
- [ ] Remote Config defaults + fetch; all keys in ARCHITECTURE §9 wired
- [ ] Analytics events from the generated enum; Crashlytics with no PII keys
- [ ] Preset OTA sync (WorkManager / BGTask + foreground) from `https://imroshan12.github.io/scan-fit/presets/v2/presets.json`, ETag, format checks (no signature since 2026-10-20), version monotonicity, "Spec updated" badges on exams whose files changed
- [ ] Live photo coach (§7) with thresholds from Remote Config; practice-only copy. _Moved to v1.1 (lean v1.0, 2026-10-20)._
- [ ] My Kit retention by plan (user decision 2026-10-15): free users keep originals 30 days, Pro until deleted; "Make all files" from My Kit (Pro, PRD F11)
- [ ] "Did the portal accept it?" one-tap prompt 24 h after a save (local notification only if permission was already granted; otherwise an in-app card on next open)

**Exit gate:** purchase + restore work on real devices (Play test cards/UPI test, StoreKit sandbox);
the 21st save opens the paywall and a purchase unblocks it; a preset change published to the CDN appears in the app within
one foreground cycle, and a broken, older or unsupported bundle is rejected (format checks; presets are not signed).

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
