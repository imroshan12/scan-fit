# HANDOFF.md — where work stopped (read this first when picking the project up)

Last updated: **2026-10-20**. Current phase: **Phase 3 → launch prep**. Target: **Mr Doc v1.0 on both stores in the first
week of December 2026**, week by week in `docs/LAUNCH_PLAN.md` (Phase 2 device QA still open, docs/ROADMAP.md).
Latest commit: `1209812 UI design update` (Home + Kit previews). **Uncommitted:** the launch-plan docs, the Mr Doc
rename, free saves + paywall + Settings on both apps, the website (`web/`) and `docs/STORE_LISTING.md` (§4 first two
entries).
The user commits; a session never runs `git add/commit/push`.

## 1. State in one screen

| Area                                         | State                                                                                                                                                                                                                                                                                          |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Home                                         | Redesigned 2026-10-20 (user): editor first. "What would you like to do?" + a grid of large tool cards (Exam forms, Resize photo, Signature, Images to PDF, Compress PDF, Merge PDFs, ID card, Check a file, Thumb impression, Handwritten page), pinned exams, compact "Coming soon". The old search/categories/Popular list is now **Exam forms**. Tabs are Home · My Kit · Settings (Tools tab removed). iOS checked on the iPhone 17 simulator; Android only in previews. |
| Exam checklist                               | Photo/ink rows reopen retained files. Verified private drafts show "Ready · KB" after relaunch; historical Saved takes precedence. Missing/invalid/expired drafts never show Ready. Broader PDF/live-photo flows remain open.                                                                  |
| Photo flow                                   | Built on both apps, including save, retry, cancellation and Done after success. Physical-device camera/navigation confirmation remains open (§3).                                                                                                                                              |
| Share / Save all                             | Built on both apps (2026-10-13): Share under Save (exact review bytes, never Saved); Save all on the checklist (every Ready row, verified per file, failures isolated, retry re-plans). iOS one Files picker for all files. Hand-checked on the iPhone 11 Pro Max simulator. |
| Export / save                                | Single-file save + verify-after-write plus private 30-day retained final drafts (2026-10-06), shared by photo/ink. Restored reviews re-save identical bytes. Save all, share, Open folder, cleaned originals and export history remain open.                                                   |
| Ink flows                                    | Built on both apps (2026-10-05): signature, triple signature, thumbs, NEET fingers, declaration. Free crop, cleanup variant by type, crisp black / darker ink, one-time handwriting tick, save + verify. Verified end to end on the iPhone 11 Pro Max simulator; Android hand check open (§5). |
| Review                                       | Built on both apps: match note (2026-10-05), Before/After + hold-to-compare and output-derived KB/dimensions/JPG chips (2026-10-06). No Fix button in exam flows (decision, §2). Gesture/accessibility device QA remains open.                                                                 |
| Custom size                                  | Built on both apps (2026-10-13): ALGORITHMS §11, a `FlowTarget` runs the same photo/ink flows for any px/cm/KB size. Form with recent sizes, from Tools and Home. JPG only. |
| Thumb cleanup                                | Rewritten 2026-10-13 (user report: details cleared). Paper-only background, linear stretch, box-centred square; 3 `thumb_preservation_cases`. Device check with a real inked thumb open. |
| Checker                                      | Built on both apps (2026-10-15): ALGORITHMS §12. Pick a file (gallery/Files, image or PDF) → its own problems → kind chips → exam list → per-slot verdict → "Make it fit" refits a verified copy; Save (records Saved) and Share. From Tools and Home. Hand-checked on the iPhone 11 Pro Max simulator. |
| My Kit                                       | Built on both apps (2026-10-15): ALGORITHMS §13. Three views (Prepared · Originals · History). Save to My Kit on new photo/ink reviews; "Use for exam…" starts a slot's flow from an original; every verified export listed with 30-day re-share; Clear history. Hand-checked on the iPhone 11 Pro Max simulator (signature → Save to My Kit → Originals → Use for exam → IBPS save → History → Share); Android not walked by hand. |
| My Kit previews                              | Built on both apps (2026-10-20, ALGORITHMS §13.3 Previews): thumbnails on Prepared and History rows (PDF = first page), tap a History file → full preview (image or up to 20 PDF pages) with Share, and Save to My Kit shows the picture first (Save / Cancel). VM tests only; Kit not walked by hand (the simulator Kit was empty). |
| PDF tools                                    | Built on both apps (2026-10-15/20): ALGORITHMS §6 + §9.10. Tools → Images to PDF · Compress · Merge · ID card; an exam's PDF row opens the same PDF maker (records Saved); Checker "Make it fit" makes a PDF for PDF slots; PDF exports join My Kit History. Grayscale + cleanup on by default, Tools need a Max KB (user decisions). Not run by hand on a simulator or device yet. |
| Mr Doc rename                                | Done 2026-10-20 except the store ids (waiting on the user): every "ScanFit" string, iOS display/bundle name via spec keys, Home wordmark, `web/`, Android `Downloads/Mr Doc/<Exam>/`. Checked on the iPhone 17 simulator (home-screen name + Home wordmark). |
| Free saves + paywall                         | Built on both apps 2026-10-20 (ALGORITHMS §14): local `SaveGate` on every Save/Share/Save all, paywall over the app, "N free saves left" on reviews. Billing is the stand-in `LocalBilling` (debug: fake ₹99/₹199 that grant Pro for the session; release: store unavailable). No RevenueCat, no account sync yet. Delayed-billing lifecycle tests on both apps; iOS native simulator smoke passes (fake purchase/Restore/Retry/Hindi). Android UI and real billing QA open. |
| Settings                                     | Full Settings on both apps 2026-10-20: Pro/free saves/Get Pro/Restore, language, unverified, specs version, privacy (analytics opt-out stored, not yet used), policy/terms/help links (placeholders), Report a problem (mailto), disclaimer, version. iOS Pro/free-save/Restore simulator smoke passes; remaining rows and Android still need a hand walk. |
| Website                                      | Drafted 2026-10-20 in `web/`: landing (editor first, tools, pricing, FAQ), privacy policy, terms, help, delete-account (Play requirement), shared `site.css` with token colours, light/dark. Checked in the browser pane at 375 px and 1280 px, light and dark, no horizontal scroll, all internal links 200. User details filled in 2026-10-20; only `REVIEW:` comments for a lawyer remain. Deploys to GitHub Pages via `.github/workflows/pages.yml` once Pages is switched on. |
| Store listing                                | Drafted 2026-10-20 in `docs/STORE_LISTING.md`: names within limits, Play EN + HI (machine draft) descriptions, App Store text and keywords, Data safety / App Privacy answers for rc builds (nothing collected) and for v1.0 (sign-in, RevenueCat, Firebase), other Play forms, graphics and screenshot list. |
| Screenshot tests                             | Removed for good (user decision 2026-10-03). Don't add them back (CLAUDE.md Definition of done).                                                                                                                                                                                               |

Gates last run (2026-10-20, presets signing removed): Android `spotlessCheck detekt testDebugUnitTest :core:match:test
:core:model:test lint :app:assembleDebug` green, `:app:assembleRelease` green with only `assets/presets/presets.json`
in the APK (the embed task now empties its folder first). iOS strict SwiftLint (0), Core SwiftPM (218) and Features
SwiftPM (170) with no warnings, app tests and DesignSystemTests on the iPhone 17 simulator (TEST SUCCEEDED), unsigned
`archive.sh --no-sign` green with only `presets.json` in the app. Spec: tool tests (67), `make_presets_load_cases.py
--check`, `gen_strings/gen_tokens/gen_analytics --check`; `release_preflight.py`: nothing blocks (3 warnings).
Previous gates (2026-10-20, paywall QA follow-up): Android `spotlessCheck detekt :feature:paywall:testDebugUnitTest
:feature:paywall:lintDebug :app:assembleDebug` green (9 paywall tests; no Android device attached, native Android UI
still open). iOS strict SwiftLint clean, full Features SwiftPM (170), normal app tests (4) and `ScanFitSmoke` native UI
tests (4) green on iPhone 11 Pro Max / **iOS 26.5**, not iOS 17. Smoke: free-save display, no-purchase Restore, dismissal,
exhausted seeded quota → fake Lifetime purchase → Thanks → Pro, unavailable-store Retry/Restore, Hindi at the largest
accessibility font (products stacked, legal links reachable). No screenshot baselines. Three delayed-billing
`paywall_lifecycle_cases` pass on both platforms; test tools (68) and strings/tokens checks green. The smoke container is
Debug-simulator-only, with isolated prefs, in-memory quota and no draft/Kit stores; it never touches real documents or
quota. Real exports/providers, real billing, Android native UI, VoiceOver/TalkBack and minimum-OS QA remain open.
Previous gates (2026-10-20, rename + free saves + paywall + Settings): Android `./gradlew spotlessCheck detekt
testDebugUnitTest :core:match:test :core:model:test lint :app:assembleDebug` green (473 unit tests, 446 before;
lint clean on every module). iOS strict SwiftLint (0), Core SwiftPM all suites incl. DesignSystemTests on
Swift 6.4 (fixed a stale `home.title` expectation left by the Home redesign), Features SwiftPM (167) with no warnings,
xcodegen + app tests on the iPhone 17 simulator (TEST SUCCEEDED). Spec: `test_tools.py` (68), `gen_strings.py --check`,
`gen_tokens.py --check`. Both platforms pass all 11 `save_quota_cases`. `release_preflight.py` now also warns (blocks with
`--final`) on placeholder `AppLinks` and the stand-in `LocalBilling`; its presets-signature blocker is the local dev dist.
Not done: a hand walk of Settings/paywall on either platform, Android visual check, any device.
Previous gates (2026-10-20, Home + Kit previews): Android `./gradlew spotlessCheck detekt testDebugUnitTest
:core:match:test :core:model:test lint :app:assembleDebug` green (446 unit tests, lint clean). iOS strict SwiftLint (0),
Core SwiftPM (209) and Features SwiftPM (153) with no warnings, app tests on the iPhone 17 simulator (TEST SUCCEEDED).
Spec: `test_tools.py` (66, via `unittest discover`), `build_presets.py`, `gen_strings.py --check`, `gen_tokens.py --check` (lowest text
contrast 5.40:1). Hand-checked: the iOS Home grid, Exam forms and Coming soon on the iPhone 17 simulator. Not done: an
Android visual check (previews only), a Kit preview walk with real files, the iPhone 11 Pro Max build, any device.
Previous gates (2026-10-20, PDF tools): Android `./gradlew spotlessCheck detekt testDebugUnitTest :core:match:test
:core:model:test lint :app:assembleDebug` green (427 unit tests, lint clean). iOS strict SwiftLint (0), Core SwiftPM (all
suites, incl. `PDFTests`: every `pdf_*` case through the real CoreGraphics renderer, ~90 s in debug) and Features SwiftPM
(145 tests) with no warnings, app tests on the iPhone 17 simulator (TEST SUCCEEDED). Spec: `test_tools.py` (66),
`build_presets.py --sign-dev/--verify`, `gen_strings.py --check`, `gen_tokens.py --check`. Not done: the iPhone 11 Pro Max
build/hand walk and any device check. Seen once: `InkFlowViewModel` "share stages exactly the review bytes…" failed in a
full Features run and passed on re-run (shared share-staging folder across parallel suites; pre-existing, not PDF code).
Previous gates (2026-10-15, evening, My Kit): Android `./gradlew spotlessCheck detekt testDebugUnitTest :core:match:test
:core:model:test lint :app:assembleDebug` green (401 unit tests, lint 0 issues). iOS strict SwiftLint (0), Core (200) and
Features (131) SwiftPM suites with no warnings, app tests on the iPhone 17 simulator, a warning-free build on the iPhone 11
Pro Max simulator plus the hand walk in §1. Spec: `test_tools.py`, `build_presets.py`, `gen_strings.py --check`,
`gen_tokens.py --check`. Both platforms pass all `kit_size_cases`, `kit_history_cases` and `kit_id_cases`. Coverage was not
re-measured; the new engine code (`KitOriginalImage`) has direct tests on both platforms.
Previous gates (2026-10-15, Checker): Android 352 unit tests; iOS strict SwiftLint, full suites, app tests on the iPhone 17
simulator and a build on the iPhone 11 Pro Max simulator; spec 61 tool tests. Both platforms pass all 10 `checker_cases`.
Previous gates (2026-10-13): Android 332 unit tests. iOS strict SwiftLint, full Core and Features SwiftPM suites, app tests on the iPhone 17
simulator and a build on the iPhone 11 Pro Max simulator; spec 59 tool tests and `gen_strings.py --check`. Both platforms pass
all 18 `custom_size_cases` and 3 `thumb_preservation_cases`. Coverage was not re-measured this session.
Earlier gates (2026-10-06): Android 315 unit tests. iOS: strict SwiftLint **from ios/**, full Core (166 tests) and Features (90 tests) SwiftPM regressions, DesignSystemTests
(11 including 4 decode-lifecycle checks), and iPhone 17 simulator app build. Spec: 57 tool tests, `gen_strings.py --check`,
debug preset build/sign/verify. Both platforms consume all eight `export_cases`, 18 `crop_cases`, 6 `match_note_cases` and
8 `review_check_cases` and all 11 `draft_cases`. Draft tests also cover actual file relaunch, atomic replacement failures,
symlinks/size limits, cancellation, current/retired presets, Ready/Saved precedence and restored Files save callback ordering.
All 3 `ink_preservation_cases` pass on both platforms, with native UPSC ESE triple-signature fit/encode regressions.
Engine coverage refreshed: Android inspect 95.1%, match 97.8%, imaging 98.8%; iOS Inspect 96.7%, Match 96.6%, Imaging 97.8%.
Device/provider QA remains open. New `review.*`, `draft.*` and `kit.*` Hindi messages are
drafted with `TODO_HI:`. The spec suite still emits pre-existing unclosed-file `ResourceWarning`s in `exam_search.py`.

## 2. Decisions made with the user (not obvious from the code)

- **No presets signing (user, 2026-10-20):** "I don't need this seeding condition, we can simply check the format. This thing
  over-complicating the things." Both apps now only run format checks on presets (spec/README.md "Loading presets": JSON header,
  `schema_version` ≤ 2, newer `presets_version`, full decode). Accepted risk, told to the user: whoever controls the repo or the
  site could publish wrong rules (so: GitHub 2FA). The `PRESETS_SIGNING_KEY` secret and `presets-production` environment are
  unused; the user deletes them (OWNER_STEPS A5).

- **Launch identity (user, 2026-10-20):** app id `com.roshtech.scanfit` is final on both stores; the seller is the user
  as an individual (Sarvesh Roshan; Play developer name the same); support email `pro.sarvesh.roshan@gmail.com`; the
  website is GitHub Pages (`https://imroshan12.github.io/scan-fit/`, the repo is public; `.github/workflows/pages.yml`);
  courts: Gurugram (Gurgaon), Haryana. Filled into `web/`, both apps' `AppLinks`, STORE_LISTING, OWNER_STEPS.

- **Launch and money (user, 2026-10-20, question cards):** store name **Mr Doc** (code keeps `scanfit`); lean v1.0 in
  the first week of December (Scan, guide sheet, live coach → v1.1); **20 free saves in total** (the user's own answer:
  "Maximum of 20 saves, not daily cap"), then Pro at ₹99/year or ₹199 lifetime; **no ads**; RevenueCat + Firebase
  (Crashlytics, Analytics, Remote Config). Google Play account is **personal** (12-tester, 14-day closed test is
  mandatory); Apple Developer is registered. My calls, written in PRD §6: what counts as a save (a new verified file
  leaving the app, once per file; re-share, Save to My Kit and failed saves are free), no store free trial (the 20 saves
  are the trial). **Accounts (user, later the same day):** sign-in on first launch (I recommended at the first save and
  warned about Apple 5.1.1(v); the user chose first launch), Google on both + Apple on iPhone, Firebase Auth + database
  holding only the save count; Pro follows the account via RevenueCat `logIn(uid)`. My calls: offline first launch lets
  the user in; the gate's position is one setting (fallback: first save); file hashes stay on the phone. Ask a lawyer
  about DPDP (LAUNCH_PLAN §5).

- **Rename + free saves session (user, 2026-10-20, question cards):** scope = rename + everything of week 1 that needs no
  keys (counting spec, local quota, paywall over a stand-in billing, Settings); the Android folder becomes
  `Downloads/Mr Doc/<Exam>/`. My calls (ALGORITHMS §14): a file's identity is the SHA-256 of its exact bytes; Share counts
  once the sheet opens (the app cannot see whether the user finished); Save all is decided as one batch (too few free
  saves → paywall, nothing saved, rather than a partial save); a lowered `free_saves_total` never takes back what a user
  was shown, a raised one applies at once; Pro saves are remembered but not counted; the quota record is in no-backup
  storage (file hashes never leave the phone, not even in a backup; the account copy restores the count); after a
  purchase the user taps Save again (no auto-resume); the paywall cards buy on tap (PRD "side by side", UI_UX updated from
  "segmented"). Website links were placeholders on `mrdoc.example`; since 2026-10-20 they point at GitHub Pages.

- **Editor first (user, 2026-10-20, question cards):** the app is marketed as a normal photo/PDF editor with exams built in.
  Home = a grid of large tool cards with a title and one-line description; exams are one card ("Exam forms", the old Home
  list). Cards chosen by the user: Exam forms + Check a file, Resize photo + Signature, all four PDF tools, Thumb + document.
  The user answered "Keep My Kit as it store my data" to "what happens to the Tools tab"; I read that as: drop Tools (its
  tools are all Home cards now), keep My Kit → tabs Home · My Kit · Settings. My calls: accent colours per tool family
  (exam/photo/ink/pdf/check tokens, contrast-checked), 2 columns (1 at large text), Scan/Guide sheet/Coach shown as one
  compact muted "Coming soon" list instead of dead cards. Kit previews: all three the user picked (History preview,
  thumbnails everywhere, preview before Save to My Kit).

- **PDF tools (user, 2026-10-15, question cards):** an exam's PDF row opens a PDF maker for that slot; certificate PDFs are
  grayscale + cleanup by default (both toggles); Tools require a Max KB (quick picks 100/200/300/500, default 200); merge
  and compress rasterise pages (text not selectable) so both apps hit a KB target without a PDF library. My calls: the app
  writes its own image PDF (byte-identical on both apps); a Tools limit (no minimum) never pads; a fixed quality grid,
  extended to q20 at 100 DPI because ImageIO compresses less than libjpeg; at most 20 pages; no Pro limits until Phase 4.

- **Low-end profile:** Android 4 GB RAM + API 29; iPhone 11 Pro Max on iOS 17 (the app minimum). CI uses an iPhone 11 Pro Max simulator.
- **"Show unverified exams" is ON by default** (ALGORITHMS §10). Unverified exams are listed with the "Unverified — check notice"
  badge and "likely OK" wording (rule 6 still applies). Users can turn it off; that choice is stored.
- **Name & date strip is OFF by default**, even when a preset says it is required (all such presets — the six UPSC exams — are
  unverified). The review shows a hint ("This exam may need your name and date on the photo. Check the notice.") instead (§2.4).
- **Photo sources:** camera (the system camera app — the in-app CameraX/AVFoundation camera with overlay comes with the live coach in
  Phase 3), gallery (Photo Picker / PhotosPicker) and **Files** (`OpenDocument` / `fileImporter`), all without storage permission.
- **ML Kit** (bundled face detection 16.1.7 + selfie segmentation 16.0.0-beta6) adds ~8.6 MB to an arm64 download (~10–11 MB total
  vs the 15 MB budget). Fallback if needed: Play-services Subject Segmentation (0 MB, model downloaded on first use). TECH_FEASIBILITY.
- **Match note in exam flows has no Fix button** (my call, 2026-10-05, ALGORITHMS §4): the file is made for that exam's slot, so a
  near miss only says what the other exam needs ("Needs ≥ 20 KB"). Fix belongs to the Checker / Custom resize. Revisit if the user
  wants "make a copy for SSC CGL" from a review. The preview names follow §9.7 order (EXACT first), so an IBPS signature lists
  NEET UG / JEE Main first (range-mode slots are EXACT; IBPS's own slot is ACCEPTED because the file is upscaled to reach 10 KB).
- **My Kit (user, 2026-10-15):** originals are saved only by the explicit "Save to My Kit" button on a review (never
  automatically), and kept until deleted for now. Later (Phase 4): free users 30 days, Pro until deleted. Export history
  lists every verified save with a private copy kept 30 days for Share. My choices: max 30 originals (a full Kit asks the
  user to delete one; nothing is evicted), newest 100 history entries; "cleaned original" = the flow's upright decoded
  picture (≤ 2400 px, q92, metadata stripped), not the ink-cleaned crop, because each slot re-crops and cleans for its own
  aspect. No Room/SwiftData: one JSON index + blob files (ARCHITECTURE §3).
- UPSC ESE / CMS are low-confidence presets ("assumed common UPSC rules"). Offered to the user, not started: verify them against the
  official notices, and/or a search hint when a query only matches hidden exams.

## 3. Device reports and remaining confirmation

### Save sheet swiped away → endless spinner (user report, 2026-10-15)

- **Symptom (iOS):** dismissing the Files save sheet without saving left Save as a spinner forever, Share disabled; in the
  ink/photo flows Back is hidden while saving, so the user was stuck. Reproduced on the iPhone 11 Pro Max simulator.
- **Cause:** the iOS 26 Files export sheet has no Cancel button; swiping it down closes the picker **without** calling
  `documentPickerWasCancelled`. `FilesExporter.presentationDismissed()` only cleared the sheet (by design, because the
  picker's result can arrive just after the dismissal — the 2026-10-04 Saved-status bug), so the save never resolved.
- **Fix (`ios/Packages/Core/Sources/ScanData/FilesExporter.swift`, every flow + Save all):** a dismissal still is not a
  cancellation, but if no picker result arrives within a 2 s grace the save resolves as cancelled (`.idle`; Save all → all
  idle) and staging is removed. A counter makes a late check a no-op for a newer save. 3 new tests (single, Save all, stale
  check); removing the cancel fails both dismissal tests in 2.5 s. Checked on the simulator: swipe → Save returns; a real save
  (with "Replace") still verifies and shows Done. Android is unaffected (its picker always returns, null on Back).

### Thumb impression loses detail (user report, 2026-10-13)

- **Cause (two bugs, both platforms):** (1) the thumb was normalised by a *local* mean (`L / blur(L)`, σ = shortSide/30) —
  correct for thin signature strokes, wrong for a print: any heavily inked area wider than the blur (the core where ridges
  merge, heavy pressure) normalised to ~white, and the 2–98 % stretch + gamma 0.8 lightened faint ridges further. (2) the
  square was centred on the ink *centroid*, so heavier ink on one side clipped the opposite edge.
- **Fix (ALGORITHMS §9.5 thumb):** candidates = local contrast OR Otsu-dark, dilated; the paper level comes from non-candidate
  pixels only (normalised convolution, σ = shortSide/8, regularised to the median paper); linear stretch from the darkest ink
  to near-white paper with a mild 1.25 exponent (never lightens ink); the square is centred on the robust (0.5–99.5 %) box of
  visible ink (`< 240`), so it always contains the whole print. Signature/document cleanup unchanged.
- **Tests:** 3 parametric `thumb_preservation_cases` (heavy core under shadow, offset heavy ink, faint ridges) fail on the old
  code and pass on both platforms; a real IBPS left-thumb fit keeps the dark core after JPEG. Mutation-checked on both.
- **Open:** confirm with the user's real thumb photo; a thumb taken off-centre with a lot of background is the riskiest case.

### iOS reports: missing signature ink and empty My Kit (2026-10-06)

- **Signature:** user reported missing parts after preparing a signature for UPSC ESE. Confirmed a destructive cleanup
  path: 3×3 binary opening + small-component removal erased every pixel of a 1-pixel stroke plus detached dot (112 → 0).
  Signature and triple-signature variants now keep the Sauvola mask unchanged; document morphology and thumb cleanup
  stay unchanged. All 112 pixels survive the regression. Three shared preservation cases and a full UPSC ESE fit/encode
  test retain all three stroke groups and disconnected dots on both platforms. Fine background marks may also remain:
  prefer preserving real ink; use crop to exclude unrelated marks.
- **My Kit:** the tab was still the Phase 0 unconditional empty placeholder, not a persistence failure. It now lists
  currently verified retained photo/ink files by exam, including unverified exams hidden from Home, and opens the existing
  read-only review. Kit maintains loading/empty/error/retry states, revalidates on entry/foreground/revisions and keeps
  only metadata in its model. Separate navigation returns Done to Kit. No new store or external dependency added.
- **Recovery/QA:** previously retained/exported damaged signatures cannot regain deleted strokes; select Replace file
  and re-import the original in the updated build. Files from before draft retention, expired files or failed private
  retention cannot be recovered from Saved alone. The user's actual source has not been inspected; low-contrast ink may
  expose an additional thresholding issue. Ask for anonymized original/result if loss remains. Physical-device confirmation
  is still required for both fixes. Kit originals, cross-exam reuse and export history remain unimplemented.

### Open issue: "Done does nothing" on the user's physical device

- Not reproducible on the simulator: Done pops back to the exam from Popular, from search, and after cancelling the Files picker.
- Untested path: the **camera** (full-screen cover over the flow) — the likely trigger for `@Environment(\.dismiss)` misbehaving.
- Fix applied (unconfirmed on device): iOS no longer uses `dismiss()` in the flow. `RootView` owns `NavigationStack(path: $homePath)`
  and passes `PhotoFlowView(onExit:)`, which removes the last path element. Android: Done = `navController.popBackStack()`, and the
  photo route now uses `launchSingleTop` so a double tap cannot stack two flows.
- Asked the user for: iPhone or Android + OS version, photo source (camera/gallery/Files), and what happens on Done. Follow up there.
- Review now saves and verifies before offering Done; Saved persists on the exam checklist. This removes the former no-save
  behavior, but does not substitute for confirming the camera/navigation fix on the user's physical device.

### Files save missing Saved status (2026-10-04)

- User reported a photo existed in Files but neither Review nor the exam checklist showed Saved.
- Found an iOS callback-order bug: the export sheet's binding treated becoming nil as picker cancellation, which could resolve
  the save as idle before UIKit delivered the destination URL. A dismissed sheet is not evidence of cancellation.
- Fixed: presentation dismissal only clears the sheet. Only the document picker's completion/cancellation delegates resolve
  the export; staging survives dismissal until that result. Completion still re-opens and verifies the destination before
  persisting Saved. Regression tests cover dismissal before success and cancellation, plus real Files exporter → photo model
  → existing exam preferences → relaunch persistence.
- Full Features tests, focused regressions, strict SwiftLint and iPhone 17 simulator build passed. Confirmation on the user's
  device is still needed with the updated build. Files saved before the fix are not retroactively marked; save again to verify.

## 4. What changed (by area, for review)

### Test builds get 200 free saves; store graphics (2026-10-20, late)

- **Spec:** ALGORITHMS 14.2 "Where `t` comes from": the build carries the default total (20; CI test builds 200), Remote
  Config overrides later; new `free_saves_build_value_cases` (8) in cases.json with `quota.build_total` as reference.
- **Android:** `:app` gets `BuildConfig.FREE_SAVES_TOTAL` from `-Pscanfit.freeSavesTotal` (default 20; `buildConfig = true`);
  `AppModule.quotaConfig()` replaces DataModule's provider; `QuotaConfig.fromBuildValue` + conformance test.
- **iOS:** build setting `FREE_SAVES_TOTAL` (20) → Info.plist `MRDFreeSavesTotal`; `SaveGate.buildTotal(_:)` +
  `SaveQuotaTests.buildValues`; `AppContainer.live` passes it to the gate; `archive.sh --free-saves N` (prints it).
- **CI:** android/ios release jobs output `free_saves` (200 for `-main`/`-rc`/`-beta`, 20 for final tags) and pass it on.
- **Store:** `store/feature-graphic.html` → `store/play-feature-graphic-1024x500.png` and `store/play-icon-512.png` via
  `store/render.sh` (Quick Look renders the HTML, Pillow crops; headless Brave could not open a display here).
- **Checked:** Android spotless/detekt/`:core:data` tests/lint/assembleDebug; `-Pscanfit.freeSavesTotal=200` reaches
  BuildConfig; iOS ScanData tests (61), app tests on the iPhone 17 simulator, `archive.sh --no-sign --free-saves 200`
  ("free saves 200" in the archive); spec tool tests (68).

### Presets signing removed (2026-10-20, night)

- **Spec:** spec/README.md "Loading presets" (the four format checks; why there is no signature). `spec/signing/`,
  `spec/fixtures/signing/`, `make_signing_vector.py`, `rehearsal_key.sh` deleted; new `make_presets_load_cases.py`
  (deterministic, `--check`, `check()` = Python reference) → `spec/fixtures/presets_load/` (9 cases incl. empty file and
  non-object). `build_presets.py` only validates + bundles (removes a leftover `.sig`); `build_all.sh` has no key (`--release`
  is only the Hindi gate); `release_preflight.py` checks `spec/dist/presets.json` passes the header checks instead of keys;
  `cryptography` dropped from requirements. Analytics `sync_failure_reason` loses `bad_signature`; string
  `error.preset_bad_signature` removed (unused).
- **Android:** `PresetVerifier.kt`, `VerifiedDigestStore.kt` (+ tests) and Tink removed; `PresetLoadError.kt`;
  `PresetBundleLoader.load(bundle, currentVersion)`; `PresetFiles(bundle)`; `AppModule` without the digest store (background
  thread kept, renamed `presets-load`); `EmbedPresetsTask` copies `presets.json` after a shape check; `PresetLoadCasesTest`.
- **iOS:** `PresetLoadError.swift` (CryptoKit verifier gone), loader/repository as Android; `embed_presets.sh` copies
  `presets.json` after a shape check; `verify_presets.swift` + `test_verify_presets.sh` deleted; `archive.sh` checks the
  archive has `presets.json`; `PresetLoadCasesTests`; app `EmbeddedPresetsTests` use a damaged (truncated) copy.
- **CI:** no `PRESETS_SIGNING_KEY` anywhere; android/ios rehearsals build the release configuration without a throwaway key;
  ios drops the verifier step; spec drops `publish-presets`; `pages.yml` builds the bundle and publishes
  `presets/v2/presets.json` with the site (also on `spec/presets/**` changes).
- **Docs:** ARCHITECTURE §6/§10, BUILD_AND_RELEASE (sections 1, 3–7, 9–12), RELEASE_CHECKLIST, TECH_FEASIBILITY, ROADMAP
  (history notes, Phase 4 OTA + exit gate), OWNER_STEPS A5, LAUNCH_PLAN, web/presets/v2/README, spikes note.

### CI fixes after the launch-prep push (2026-10-20, night)

- **What failed:** `1209812` android + ios (the Home redesign's stale `home.title` test expectation, fixed in the next
  commit); `b82fda6` ios "Feature tests": (1) `SettingsViewModelTests` still expected `mailto:support@…` after the
  support address became the Gmail one, now compared with `AppLinks.supportEmail` (SettingsTests depends on `Config`);
  (2) a flake: `ShareStager.stage` empties its one folder, so InkFlow/PhotoFlow suites running in parallel deleted each
  other's staged file ("share waits for the handwriting tick" in CI; "share stages exactly the review bytes" earlier).
  Fix: `stage(_:filename:in:)` / `clear(in:)` take an optional folder, the photo and ink VMs have a test-only
  `shareFolder`, test models use `ownShareFolder()` (TestSupport); regression test "stages in separate folders never
  remove each other's file". App behaviour is unchanged (nil = the app's folder). `pages` failed only because Pages is
  not switched on yet (OWNER_STEPS A4). `spec.yml` `publish-presets` now runs only on manual dispatch (it left an
  approval waiting on every push and deploys nothing while the site is on GitHub Pages).
- **Verified locally:** Features SwiftPM 3 full runs in a row green (170), ScanData 60 incl. the new test, strict
  SwiftLint, DesignSystemTests via xcodebuild on the iPhone 17 simulator; Android CI command (`spotlessCheck detekt
  testDebugUnitTest lint --continue` + coverage gate) green. GitHub showed `b82fda6` android build green. Not run
  locally: the release rehearsals (`rehearsal_key.sh` overwrites `prod_public_key.b64`; CI-only) and app tests.

### Website and store listing (2026-10-20, evening)

- `web/index.html` rewritten (was a placeholder); new `web/privacy/`, `web/terms/`, `web/help/`, `web/delete-account/`,
  `web/site.css` (colours copied from `spec/tokens/tokens.json`; keep in step), `web/icon.svg` (the launcher mark) and
  `web/apple-touch-icon.png` (copy of the iOS 180 px icon). `web/_headers`: nosniff / no framing / referrer policy on the
  public pages; presets and upload-test rules unchanged. No scripts, no trackers.
- `release_preflight.py`: `WEB_PAGES` / `WEB_PLACEHOLDERS`, warns (blocks with `--final`) while a page still has a
  placeholder or a `REVIEW:` note; test added (`test_tools.py` 69 via `unittest discover`).
- `docs/STORE_LISTING.md` (new). `.claude/launch.json` (new): a `web` preview server (`python3 -m http.server 8765
  --directory web`) for the browser pane.
- My calls: the privacy policy describes v1.0 (sign-in, RevenueCat, Firebase) even though today's build has none of them
  (a policy may say more than a build does, never less); Data safety must match the uploaded build, so STORE_LISTING has
  both answer sets. Store badges are plain "coming soon" text, not the official badge artwork. A deleted account that
  signs in again starts with 20 fresh saves, like a second Google account (accepted; noted on the delete page).

### Mr Doc rename, free saves, paywall and Settings (2026-10-20)

- **QA follow-up:** delayed-billing regressions reproduced on both apps, then fixed. Both paywall VMs now version the
  presentation/product load and preserve an in-flight purchase/restore's busy state across close/reopen; stale results
  cannot replace a new offer's products/message. Retry is ignored while busy; buying a product outside the offer is
  rejected. ALGORITHMS §14.3 + 3 `paywall_lifecycle_cases`; Android `PaywallLifecycleTest` (3) and iOS
  `PaywallLifecycleTests` (3). iOS `AppContainer+UITesting.swift` is gated by `DEBUG && targetEnvironment(simulator)`
  plus `--ui-testing`; `ScanFitUITests/PaywallSmokeTests.swift` (4) uses the separate `ScanFitSmoke` XcodeGen scheme.
  Stable accessibility identifiers on Settings/Paywall; no new runtime dependency or screenshot tests.
  First Hindi smoke run selected legal links by the wrong accessibility type (SwiftUI reports them as buttons);
  corrected to identifier-based queries and verified all 4 tests. Failed-run sysdiagnose took 600 seconds locally,
  so the documented smoke command disables verbose diagnostic collection.
- **Spec:** ALGORITHMS §14 (free saves: what counts, rules, screens, storage) + `save_quota_cases` (11) with reference
  `spec/tools/quota.py` and a drift test; §1.6/§6.6/§11 folder text → `Mr Doc`. Strings: every "ScanFit" → "Mr Doc";
  new `infoplist.display_name` / `infoplist.bundle_name` (spec now owns `CFBundleDisplayName`/`CFBundleName`, which Xcode
  had been extracting into `InfoPlist.xcstrings` by itself); `quota.*`, `paywall.*`, `legal.privacy_policy`,
  `legal.terms`, `settings.*` (Hindi `TODO_HI:`). `release_preflight.py` `check_launch_wiring` (+ test).
- **Android:** `:core:billing` (`Billing`, `ProProduct`, `ProductsState`, `LocalBilling`); `:core:config` `AppLinks`;
  `:core:data/quota` (`SaveQuotaRules`, `FileQuotaStore` in `noBackupFilesDir/quota`, `SaveGate`, `PaywallRequests`,
  `QuotaConfig`); `DocumentExporter(…, gate)` records after verification and exposes `gate`; photo/ink/Checker/PDF VMs
  check before Save/Share and record shares; `ExamViewModel.onSaveAll` reads all drafts, decides the batch, then saves.
  `ExportNaming.APP_FOLDER`. Design system `FreeSavesNote` + `LocalFreeSavesLeft`. `:feature:paywall`
  (`PaywallViewModel`, `PaywallHost`/`PaywallScreen`, previews). `ScanFitApp` hosts the paywall and provides the count
  (`AppNavigation` split out). Settings rewritten (`SettingsRows.kt`); `UserPreferences.analyticsEnabled`. AppModule
  provides `LocalBilling` (fake products when the app is debuggable). `:core:testing` `FakeBilling`, `FakeQuotaStore`,
  `TestSaveGate`.
- **iOS:** `Billing` module (protocol + `@Observable LocalBilling`); `Config` `AppLinks`; ScanData `SaveQuota.swift`
  (rules, `FileQuotaStore` actor, `MemoryQuotaStore`) and `SaveGate.swift` (`SaveGate`, `PaywallRequests`, CryptoKit
  SHA-256). VMs take `gate: SaveGate? = nil`; photo/ink `save()` moved next to `share()` (`PhotoFlowHelpers.swift`,
  `InkFlowShare.swift`) to stay under the 400-line lint limit. DesignSystem `FreeSavesNote` + `\.freeSavesLeft`. Paywall
  feature (`PaywallViewModel`, `PaywallView`), `SettingsViewModel` + new `SettingsView`; `AppContainer` gets `billing` +
  `gate` (`#if DEBUG` fake products); `RootView` loads the gate, sets the environment and presents the paywall as a
  full-screen cover; project.yml adds Paywall + Billing. `UserPreferences.analyticsEnabled`. TestSupport `FakeBilling`,
  `testSaveGate`.
- **Tests:** Android `SaveQuotaConformanceTest`, `SaveGateTest` (7), `LocalBillingTest` (3), `PaywallViewModelTest` (6),
  `SettingsViewModelTest` (7), photo save gate (3), Save all gate (2), analytics pref; iOS `SaveQuotaTests` (8),
  `LocalBillingTests` (3), `PaywallViewModelTests` (5), `SettingsViewModelTests` (5), photo export gate (2), Save all
  gate (2).

### Editor-first Home and My Kit previews (2026-10-20)

- **Spec:** `tokens.json` accent roles `photo/ink/pdf` (+ `Container`, `on…Container`, light and dark; `gen_tokens.py`
  checks their contrast); strings `home.title` reworded, `home.subtitle`, `home.card_*` (+ `_body`), `home.coming_soon_heading`,
  `tools.*_body`, `kit.preview_*`, `kit.save_preview_*` (Hindi `TODO_HI:`), `tab.tools` removed. ALGORITHMS §6.6 (Home's PDF
  cards), §11 (Home cards open Custom size with the kind chosen), §13.3 "Previews". UI_UX principle 1 "Editor first", IA,
  Home and Exam forms sections.
- **Android:** design system `ScanFitIcons` (tool glyphs as `ImageVector`s, no icons-extended dependency), `ToolCard`,
  `ToolAccent`, `SaveToKitButton(preview =)` + `SaveToKitDialog`. `:feature:home`: new `HomeScreen` grid + `HomeTool`,
  old list → `ExamBrowserScreen` (route `exams`), previews in `ExamBrowserPreviews.kt`. `ScanFitApp`: tabs Home/Kit/Settings,
  `route(HomeTool)`, `custom?doc=…`, `kit/history`; `ToolsScreen.kt` deleted. `CustomViewModel` reads `doc` from
  `SavedStateHandle`. Photo/Ink VMs `originalPreview()` (≤ 720 px). `:feature:kit` depends on `:core:pdf`: `KitImages.openPdf`
  / `pdfFirstPage`, `KitViewModel.thumbnail`, `KitHistoryViewModel.thumbnail`, new `KitHistoryPreviewViewModel` +
  `KitHistoryPreviewScreen`, `Thumbnail` in `KitComponents`.
- **iOS:** DesignSystem `ToolCard`/`ToolAccent`, `SaveToKitButton(preview:)` sheet. Home: new `HomeView` grid (typed
  `Target`), `ExamBrowserView` (old list, search always visible), `HomePreviews`. ScanModel `CustomSizeRoute(doc:)`,
  `ExamBrowserRoute`, `KitHistoryRoute`. `RootView` tabs Home/Kit/Settings; `ToolsView.swift` deleted (xcodegen re-run).
  `CustomViewModel(doc:)`. Kit package depends on `PDF`: `KitImages` PDF pages via `CGPdfOpener`, `KitThumbnail`,
  `KitHistoryPreviewViewModel` + `KitHistoryPreviewView` (+ previews in `KitPreviews.swift`).
- **Tests:** Android `KitPreviewsViewModelTest` (5), Custom `doc` test, Photo/Ink `originalPreview` tests; iOS
  `KitPreviewsTests` (5), Custom `homeCard`, Photo/Ink preview tests.

### PDF tools (2026-10-15 → 2026-10-20)

- **Spec:** ALGORITHMS §6 rewritten (6.1 jobs/sources/options, 6.2 preparing pictures, 6.3 fit, 6.4 writer, 6.5 verify,
  6.6 screens/names/Checker) and §9.10 numbers (layout, page cleanup, `num`, writer bytes, pad, fit, job checks); §9.8 PDF
  names; §12 rule 4 (PDF slots are fixable); §13.3 PDF history entries (`format`, `pages`, `exports/<id>.pdf`). Schema doc
  types `document` and `id_card` (Tools). `spec/tools/pdf.py` (reference, `--write-cases`, `--write-fixtures`), 4 sample PDFs
  in `spec/fixtures/pdfs/`, new `pdf_layout_cases` (11), `pdf_writer_cases` (6, sha256), `pdf_fit_cases` (10),
  `pdf_job_cases` (13), `page_cleanup_cases` (2), `pdf_name_cases` (5), 3 new `checker_cases`. ~62 `pdf.*`/`tools.pdf_*`
  strings (Hindi `TODO_HI:`); `checker.needs_pdf` reworded. `gen_strings.py` also rewrote the iOS String Catalog in its own
  format (the committed one had been reformatted by Xcode); that big diff is the generator's output.
- **Engine (both):** `PdfLayout`, `PdfWriter` (byte-identical to Python: sha256 cases), `PdfFit`, `PdfJob`, `PdfEngine`
  (prepares pictures once as q95 JPEGs, measures each DPI with one page raster at a time, final pass, pad), `PageCleanup`
  (Imaging; reuses the thumb's paper tone, now `InkCleanup.paperTone`). Renderers: Android `AndroidPdfOpener`
  (`PdfRenderer` over a private temp copy), iOS `CGPdfOpener` (CoreGraphics, handles `/Rotate`).
- **Export/Kit (both):** `ExportRequest.pdf: PdfExport?` (+ MIME / `pdfFileName` on Android), `verifiesPdf`
  (§6.5), Kit `ExportRecord.pages`/`copyName`, PDF copies `exports/<id>.pdf`, sweep by file name, old entries load as JPEG.
  Android `ShareEffect` sends `application/pdf` for `.pdf` names. `TestPdf` in `:core:testing` / `TestSupport`.
- **Android UI:** `FreeCropEditor` moved from `:feature:flow-ink` to the design system (designsystem now `api(:core:imaging)`).
  `:feature:pdf` (`PdfMakerTools` + Hilt, `PdfMakerViewModel`, `PdfMakerScreen`, `PdfMakerSections`, `PdfResultSection`,
  `PdfActions`, previews in 7 states × light/dark/largest font/Hindi). Routes `pdf/{tool}` (Tools rows) and
  `exam/{examId}/pdf/{docType}` (every non-photo/ink exam row). Checker: `CheckerTools.makePdf`, `FixState.Ready.pdf`,
  `Failed.pdfError`, PDF preview + facts in the fixed copy. Kit History PDF row.
- **iOS UI:** DesignSystem `FreeCropEditor` (InkCropView now uses it) and `RasterImage`; ScanModel `PdfRoute`,
  `DocSpec.pdf(...)`. `PDFTools` (`PdfMakerTools`, `PdfMakerViewModel`, `PdfMakerView`, `PdfMakerSections`, previews in 7
  states). `ExamView(pdfMaker:)` links PDF slots; `ToolsView` PDF section; `RootView` maps `PdfRoute` in Home and Tools;
  `project.yml` + xcodegen. Checker PDF fix (`FixState.pdfFailed`, `PdfFix`). Kit History PDF row.
- **Tests:** Android `PdfConformanceTest` (layout, writer sha256, fit, num), `PdfJobConformanceTest` (all job cases, the
  exit-gate compressions, with `FixturePdfOpener`), `PdfEngineTest` (9), `PageCleanupConformanceTest`, PDF names in
  `ExportNamingTest`, PDF verify/Kit tests in `:core:data`, `PdfMakerViewModelTest` (14), 2 new Checker tests. iOS
  `PDFTests` (conformance 5, job cases with the real CGPDF renderer, engine 10), `PageCleanupConformanceTests`,
  `PdfExportTests` (3), `PdfMakerViewModelTests` (12), 2 new Checker tests.
- **Found while testing:** a Tools PDF with no minimum was padded to the midpoint (fixed: no minimum, no pad); iOS could
  not reach 100 KB for a dense scan where Android could (ImageIO's quality scale; fixed by the q35–20 tail at 100 DPI); the
  text-only fixture pads on Android (fixture renderer draws no text) but not on iOS (real text), so that job case no
  longer pins the strategy.

### My Kit: originals, "Use for exam…", export history (2026-10-15, evening)

- **Spec:** ALGORITHMS §13 (13.1 originals: saving, keeping, storage, read check; 13.2 Use for exam…; 13.3 export history
  and retention). New conformance `kit_size_cases` (8), `kit_history_cases` (7), `kit_id_cases` (8) computed by
  `spec/tools/kit.py --write-cases`; `test_tools.py` checks them. 28 new `kit.*` strings (Hindi `TODO_HI:`).
- **Storage (both):** `KitStore` / `FileKitStore` (Android `:core:data` `kit/`, iOS `ScanData`): `index.json` + `originals/<id>.jpg`
  + `exports/<id>.jpg` under no-backup storage (`noBackupFilesDir/kit`, Application Support `kit`). Blob staged → re-read →
  verified → renamed, then the index; loading drops bad entries, sweeps orphans/temp files, prunes history to 100 and deletes
  copies older than 30 days. Ids are validated lowercase UUID v4 before any path. `KitOriginalImage` (Imaging) resizes to
  ≤ 2400 and encodes q92 through the patcher.
- **Flows (both):** "Save to My Kit" (`SaveToKitButton` in the design system) under Share on new photo/ink reviews; states
  idle/saving/saved/full/failed, independent of the fit verdict; a new picture resets it. Flow routes take an optional
  original id (Android `?original=<id>`, iOS `FlowRoute.original`): the flow skips the draft and the picker and opens the
  original at the crop (photo still runs face detection). A missing original → source step with "could not open".
- **History (both):** recorded only after a verified, published export: Android once in `DocumentExporter` (outside the
  try, non-cancellable, so it can never clean up a published file); iOS after `.saved` in the photo, ink, Checker and
  Save all view models.
- **Kit UI (both):** segmented Prepared · Originals · History; original's screen with preview, Delete (confirmation) and a
  "Use for exam…" sheet reusing the Checker's list (`CheckerTargetRow` and `DocKind` labels moved to the design system).
  History rows share the copy via the existing share stager; Clear history with confirmation. Done from a flow started
  from an original returns to the original's screen.
- **Tests:** Android `FileKitStoreTest` (16), `KitConformanceTest` (4), export conformance now also checks history, photo (7)
  and ink (4) Kit VM tests, Kit originals/history/original VM tests (15). iOS `KitStoreTests` (14), `PhotoKitTests` (5),
  `InkKitTests` (3), Kit VM tests (9), plus history checks in Save all and Checker tests. Previews for every new state in
  light, dark, largest font and Hindi on both. No screenshot tests.

### Checker (2026-10-15)

- **Spec:** ALGORITHMS §12 (input, "What is this file?", "Which exam is it for?", status rules, Make it fit / Save / Share /
  handwriting tick / original never modified). `spec/tools/checker.py` writes 10 `checker_cases`; `test_tools.py` checks them.
  Strings `checker.*` and `home.check_file` in en + hi (Hindi drafted `TODO_HI:`).
- **Engine (both):** `CheckerStatus.of(format, slot, verdict, unverified)` → ok / likely_ok / fix / no_fix / unknown, and
  `CheckerTargets.of(bundle, kind, query)` → every active (exam, slot) of a kind, popular first or in search order (NEET UG
  lists twice for fingers). Android `core/match/.../Checker.kt`, iOS `Packages/Core/Sources/Match/Checker.swift`.
- **Android:** `:feature:checker` (`CheckerViewModel`, `CheckerScreen`, `CheckerResult`, `CheckerTools` with a `@CheckerWork`
  dispatcher, previews in 4 states × light/dark/large font/Hindi). Route `checker` from Tools and Home (`HomeLinks`).
  7 VM tests (`FakeCheckerTools`).
- **iOS:** `Checker` feature (`CheckerViewModel`, `CheckerView`, `CheckerTargetSection`, `CheckerKindChips` with a small
  `WrappingLayout`, `CheckerTools`, previews). `CheckerRoute` (ScanModel) pushed from Tools and Home; `RootView` maps it in both
  stacks; `project.yml` adds the product. 6 VM tests (`swift test --filter CheckerViewModel`); the handwriting gate was
  mutation-checked (removing it fails 3 expectations).
- **Hand check (iPhone 11 Pro Max simulator):** Tools → Checker and Home → "Already have a file? Check it." → gallery →
  `signature_paper_shadow.jpg` (605 KB, 2400×1400) → Signature → IBPS PO → "Too big" + "shape doesn't match" → Make it fit →
  15 KB 273×117 (upscaled to reach the 10 KB minimum, §1.4), "Meets the IBPS PO / MT rules" → Save to Files → Saved → Done
  returns to the exam list. Found and fixed during the check: content was centred (now leading) and the kind menu had no
  label with nothing chosen (now wrapping chips, like Android's FilterChips).
- **Decisions (mine, revisit with the user):** one Fix that refits the copy to the chosen slot, not per-issue buttons; no PDF
  fixes yet; the kind is always chosen by the user; the refit uses the plain fit pipeline (no ink cleanup, so a shadowed
  paper background stays — the ink flow is the way to clean a signature). No `checker_run` analytics event yet.

### Share and Save all (2026-10-13, evening)

- **Spec:** ALGORITHMS §1.6.2; 6 `save_all_cases` from `spec/tools/save_all.py`. Strings `export.share_failed`,
  `export.save_all_partial`, `export.save_all_held`, `export.files_app` (Hindi `TODO_HI:`); existing `exam.save_all`,
  `flow.saved_to`, `flow.open_folder`, `common.share` are now used.
- **Android:** `:core:data` `SaveAllPlan`, `ShareStager`/`AndroidShareStager` (cache `share/` exposed through the existing
  `${applicationId}.capture` FileProvider; cleared on app start in `ScanFitApplication`), `ExportDestinations.folderLabel`.
  `ExamViewModel` has `onSaveAll`/`onSaveAllDismissed`, `SaveAllStatus`, takes `DocumentExporter` and an IO dispatcher
  (internal constructor for tests). `SaveAllBar.kt` (+ previews) in `:feature:exams`. Photo/ink VMs take a `ShareStager`;
  `onShare`/`onShareHandled`; Review states carry `shareUri`/`shareFailed`; designsystem `ShareEffect` fires the chooser.
  Tests: `SaveAllPlanConformanceTest`, `ExamSaveAllViewModelTest` (6), share tests in both flows, fakes
  `FakeShareStager` + `FakeExportDestinations.failingTypes/requests`.
- **iOS:** ScanData `SaveAll.swift` (`SaveAllPlan`, `SaveAllOutcome`, `DocumentExporting.saveAll` default, `ShareStager`,
  `SharedFile`, `ShareStatus`); `FilesExporter.saveAll` stages every file for one picker and matches returned URLs by bytes
  (`verifyAll`, `folderName`). DesignSystem `FilesExportPicker(urls:)`, `ShareSheet`. `ExamViewModel.saveAll()` and
  `saveAllStatus`; `ExamView` bottom bar + multi-file picker sheet. Photo/ink VMs: `share`/`shareHandled`, `share` status;
  views add the Share button and sheet. `RootView` clears staged shares on start. Exams target now also uses Imaging/Inspect.
  Tests: `SaveAllTests` (6, incl. "Keep both" rename and unmatched file), `ExamSaveAllTests` (4), share tests in both flows.
- **Simulator check:** IBPS thumb → Share (sheet shows `left_thumb_IBPS-PO_240x240_….jpg`) → back → checklist "Ready · 22 KB"
  → Save all (1) → Files picker → row Saved, "1 file saved to …". That showed the provider-root folder name, now "Files".

### Custom size, thumb fix and review findings (2026-10-13)

- **Spec:** ALGORITHMS §11 Custom size (+ 18 `custom_size_cases`, `spec/tools/custom_size.py`); §3/§9.5 thumb rewritten
  (+ 3 `thumb_preservation_cases`). New strings `custom.*`, `common.next` (Hindi `TODO_HI:`); `tools.custom_resize` → "Custom size".
- **Model:** Android `core:model` `CustomSize`/`CustomSizeFields`/`CustomSizeError`/`SizeUnit`, `FlowTarget`/`FlowSlot`,
  `DocType.wire`; iOS `ScanModel` the same, `FlowRoute` now wraps a `FlowTarget` (`init(custom:)`), `CustomSizeRoute`.
- **Flows:** photo + ink VMs take a target (iOS `init(target:)`, Android nav args incl. `custom`), resolve it once, and skip
  drafts/Saved/handwriting for Custom. `ExportRequest.recordsSaved` (Android). Shared `SlotVerdict` replaces the duplicated
  verdict code in four review files.
- **Custom feature:** Android `:feature:custom` (`CustomViewModel`, `CustomScreen`, `CustomActions`, previews, 6 tests),
  routes `custom` and `custom/<photo|ink>?custom=<text>` in `ScanFitApp`, Tools row + Home link. iOS `Custom` target
  (`CustomViewModel`, `CustomView`, previews, `CustomTests`), Tools/Home stacks in `RootView`, `project.yml` + xcodegen.
- **Review findings fixed along the way:**
  - iOS retained a draft for every rendered file, even one that missed the rules; Android only for passing files. iOS
    now matches (ALGORITHMS §1.6.1 says only verified files are kept).
  - `KitViewModelTest` was flaky (~1 in 3 full runs): the draft read ran on the real IO dispatcher and resumed after the
    test reset Main. `KitViewModel` now takes its IO dispatcher (Hilt still injects `Dispatchers.IO`).
  - Lint: `HomeRoute`'s new callback is required, keeping `modifier` the first optional parameter.
- **Not changed, recommended next (architecture):** the photo and ink VMs still duplicate ~150 lines each per platform
  (render versioning, draft restore, save). Extract a shared `ReviewSession` (render generation, retain policy, save
  lifecycle) before the Checker adds a third flow. `PhotoFlowViewModel.swift` sits at the 400-line SwiftLint limit.

### Signature preservation and My Kit prepared files (2026-10-06)

- **Spec:** ALGORITHMS §3/9.5 now preserve signature masks, correcting the stale wording that called triple signatures
  document cleanup. Three `ink_preservation_cases`; §1.6.1 adds the Kit prepared-file browser contract. Shared Kit empty/
  error copy updated and generated in both languages.
- **Engines:** conditional morphology in both `InkCleanup` implementations; shared count/trim/coordinate conformance
  and native UPSC ESE triple-signature fit, JPEG inspection and slot-match tests. Document/thumb behavior unchanged.
- **Kit:** Kotlin `KitViewModel`/`KitScreen` and Swift `KitViewModel`/`KitView`, Core-only dependencies, native exam sections
  with confidence badges and FlowRoute links. App owns independent Kit navigation. Tests cover metadata ordering,
  active/hidden-low-confidence exams, actual filesystem relaunch, corruption/expiry/current specs, revisions, cancellation
  and retry; previews include light/dark/Hindi/largest text. Cleaned originals, cross-exam reuse and history remain open.

### Retained drafts and Ready rows (2026-10-06)

- **Contract:** ALGORITHMS §1.6.1 and 11 `draft_cases`. One final JPEG per exam/document slot, automatically retained after
  full verification. Availability is revalidated against current trusted rules, with a strict 30-day age limit. Drafts
  are not cleaned originals, history or user-visible exports, and never record Saved.
- **Storage:** Android `:core:data` `draft.DraftStore` / `FileDraftStore`, singleton via Hilt in `noBackupFilesDir/drafts`;
  iOS `ScanData.DraftStore` / actor `FileDraftStore`, shared via AppContainer in backup-excluded Application Support.
  Versioned JSON records hold bytes/time under opaque SHA-256 names. Temp write, re-read verification and atomic replace
  preserve the previous good draft on failure. Both reject symlinks, unsupported records, oversized data and invalid age.
  No external dependency or history DB added. Invalid drafts are removed best effort when read.
- **Flows:** photo/ink persist current successful render bytes before publishing review; cancelled/superseded renders
  cannot commit obsolete output. Retention failure warns but leaves the in-memory file exportable. Valid retained files
  open directly in read-only review without face detection/cleanup/fitting; final-preview decode still runs off-main.
  Only the final JPEG is stored, so restored reviews have no Before image or processing controls. Replace file / Back go
  to source selection and preserve the old draft until a verified replacement succeeds. Signature confirmation still gates Save.
- **Checklist:** Saved → Ready · rounded KB → Not started. Ready refreshes on entry, foreground and store changes using
  current presets; removed/nonconforming/expired files and retired exams cannot leave stale Ready claims. Saved remains
  historical. Previews cover Ready/read-only review, Hindi, largest text, light and dark; no screenshot tests added.
- **Validation:** Android full formatting/detekt/tests/lint/debug-build gates, iOS full Core/Features suites, strict lint,
  simulator build and spec tools pass. Real-device relaunch/disk-full/provider/accessibility checks remain open.

### Review comparison and verdict chips (2026-10-06)

- **Spec:** ALGORITHMS §4 defines Before as the oriented selected crop before effects, After as the final JPEG, a stable
  letterboxed frame, hold/release comparison and display-only selection. Eight `review_check_cases` pin technical status
  mapping from `SlotEvaluation` (`UNKNOWN` never green). New shared `review.*` labels and accessibility messages (EN/HI).
- **Both apps:** `Match.ReviewChecks` carries size/dimensions/JPG status from the same re-inspected bytes used for the slot
  verdict. Every photo/ink render retains its bounded pre-effect crop in Review. Shared `BeforeAfterImage`, `VerdictChip`
  and `ReviewChecksRow` replace duplicated previews/summary lines. Before/After uses a native segmented selector, After
  initially; press-and-hold temporarily shows Before. Conversion/decoding is off the UI thread. No new external dependency.
- **Safety:** comparison never renders or changes Saved/export bytes. Pending debounce, fitting and failures hide stale
  output/chips. Low-confidence presets keep their separate "Likely OK · Unverified" verdict; green technical chips do not
  promise portal acceptance. DPI/privacy remain part of export verification, not the JPG chip.
- **Tests/previews:** shared conformance, output-vs-fit-report checks, progressive rejection, unknown slots, crop retention,
  save-after-debounce and unchanged export bytes. iOS decode tests cover stale results and cancellation. Light/dark/Hindi/
  largest-font previews compile. No screenshot tests added. Manual gesture, accessibility and minimum-device QA remain open.

### Match note session (2026-10-05, after the ink flows)

- **Spec:** ALGORITHMS §4 "Match note on review", §9.7 "Match note" (+ each entry carries its slot's `size_kb`); 6
  `match_note_cases` computed by the new `spec/tools/match_note.py --write-cases`; `test_tools.py` checks them.
  New strings `match.likely_ok_count`, `match.preview_more`, `match.need_*`, `match.sheet_*`, `match.verdict_*` (Hindi `TODO_HI:`).
- **Android:** `:core:match` `MatchNote` (+ `ExamRef`, `Need`, `QuickFix`, `MatchGroup`, `MatchNote.of(facts, exams, popularity)`),
  `MatchEntry.sizeKb`; `MatchNoteConformanceTest`. `:core:designsystem` now depends on `:core:match`: `MatchNoteCard` (card +
  `ModalBottomSheet`), `MatchNoteSamples` (preview data + card previews). Photo and ink `Ready` results carry `note`; the VMs
  keep the bundle's exams + popular list. VM tests for both.
- **iOS:** Match `MatchNote` (same API), public inits for `MatchEntry`, `MatchResult`, `SizeKB`; `MatchNoteConformanceTests`.
  DesignSystem depends on Match: `MatchNoteCard`, `MatchNoteSheet`, `MatchNoteSamples`, previews. `ReviewReady` / `InkReady`
  carry `note`; `PhotoFlowViewModel.ready` moved to a private extension (SwiftLint type-body length). VM tests for both.

### Ink flows session (2026-10-05)

- **Spec:** ALGORITHMS §3 "Flow (both apps)"; §9.5 doc type → cleanup variant / match kind (signature + triple → SIGNATURE_CLEANUP,
  thumbs + fingers → THUMB_CLEANUP, declaration → DOCUMENT_CLEANUP), review options (crisp black: signature on, document off;
  "Darker ink" factor 0.3–0.9 in steps of 0.1, debounced 300 ms; none for thumbs), free crop `resize` (min side 32 px), the one-time
  handwriting confirmation for signatures. The coverage quality gate only warns. `crop_cases` +6 resize cases (18 total) from
  `spec/tools/photo_crop.py --write-cases`. New `_common.replace_json_array` rewrites one array in `cases.json` whatever its
  formatting (the user reformatted it with tabs; `photo_crop.py` and `exam_search.py` use it). New `ink.*` strings (EN + `TODO_HI:`).
- **Shared export layer (moved out of the photo flow):** Android `:core:data` package `app.scanfit.core.data.export`
  (`DocumentExporter`, `ExportVerifier(kind)`, `ExportRequest(examId, examName, spec, kind, bytes)`, `SaveState`/`SaveResult`,
  `ExportDestinations`/`AndroidExportDestinations`), plus `ImageSource` (bounded read, camera capture URI under
  `${applicationId}.capture`, discard). iOS `ScanData` `ExportRequest(bytes, filename, spec, kind)`, `ExportOperation`,
  `DocumentExporting`, `FilesExporter`/`FilesExportTransport`; `ScanModel.ExportState`. Photo flow now uses these
  (`PhotoSaveState` → `SaveState`, `FilesPhotoExporter` → `FilesExporter`, etc.).
- **`DocKind.of(DocType)`** on both platforms (inverse of `slotTypes`; PDF-only slots → PDF_DOCUMENT), with tests.
- **Handwriting preference:** `UserPreferences.handwritingConfirmed` / `confirmHandwriting()` (key `handwriting_confirmed`), both apps.
- **Shared UI:** Android designsystem `NoticeCard` / `NoticeKind`. iOS DesignSystem `NoticeCard`, `ExportStatusView`,
  `FilesExportPicker`, `CameraPicker` (moved from PhotoFlow). Crop frames (photo + ink, both apps) are drawn in the primary colour
  over a white outline with handles — white-on-white was invisible on paper. The iOS ink editor insets the image so handles aren't clipped.
- **Android:** new `:feature:flow-ink` (`InkTools`/`AndroidInkTools`, `InkFlowViewModel` + `InkUiState`, `InkFlowScreen`,
  `InkCrop` free-crop editor, `InkReview`, previews, 16 VM tests). Route `exam/{examId}/ink/{docType}` (`launchSingleTop`),
  `INK_FLOW_TYPES` in `ScanFitApp`. Test support: `TestJpeg`, `FakeExportDestinations` in `:core:testing`.
- **iOS:** Features `InkFlow` target (`LiveInkTools`, `InkFlowState`, `InkFlowViewModel`, `InkFlowView`, `InkCropView`,
  `InkReviewView`) + `InkFlowTests` (14). `PhotoRoute` → `FlowRoute`; `RootView.flow()` picks Photo or Ink by doc type. Imaging
  `CropAdjust.resize` / `CropCorner`, public `InkResult` init. TestSupport `TestJpeg`, `ExportFixtures`. `project.yml` + xcodegen.
- **Docs:** ROADMAP (ink item ticked with notes), ARCHITECTURE (core:data export, iOS shared components, FlowRoute), this file.

### Photo flow session (2026-10-04)

- **Export follow-up:** ALGORITHMS §1.6 defines lifecycle, verification, cleanup and persistent checklist status; eight shared
  `export_cases`. Android adds `PhotoExporter`, `PhotoExportVerifier`, `PhotoSaveController`, `AndroidExportDestinations`;
  pending MediaStore entries are published only after verification, API 26–28 uses CreateDocument. iOS adds `PhotoExportOperation`,
  `FilesPhotoExporter` and a `UIDocumentPickerViewController(forExporting:asCopy:)` bridge; returned destination URLs are read
  with security scope and NSFileCoordinator off the main actor. Both reject duplicate saves, block edits during saving, protect
  against stale debounced renders, preserve review for retry and clean up failed destinations best effort without false removal
  claims. Saved checklist status uses existing DataStore/UserDefaults (no new dependency, no export-history DB yet).
  Four `export.*` messages live in shared EN/HI sources; generated resources and light/dark/largest-Hindi status previews updated.
- **Spec:** ALGORITHMS §2 / §9.6 (Vision face _rectangles_; manual adjust move/zoom/rotate; step order; segmenter confidence → 0/255 at
  0.5; strip off by default) and §10 (show-unverified on by default). `fixtures/cases.json` gained `crop_cases` (12), computed by
  the new Python reference `spec/tools/photo_crop.py --write-cases`; `test_tools.py` checks them. `gen_strings.py` now also writes
  `ios/ScanFit/InfoPlist.xcstrings` from `infoplist.*` keys (camera permission text, EN + HI); a test keeps the English fallback in
  `ios/project.yml` equal to `en.json`. New strings: `photo.*` (Hindi drafted, `TODO_HI:`).
- **Android:** `:core:imaging` `CropAdjust`, `PersonMask`, `Raster.toBitmap()`; `:core:vision` `MlKitFaceDetector`,
  `MlKitPersonSegmenter`; `:feature:flow-photo` (`PhotoFlowViewModel`, `PhotoTools`/`AndroidPhotoTools` + Hilt module, screens split
  into `PhotoFlowScreen`, `PhotoCrop`, `PhotoReview`, `PhotoFlowPreviews`; FileProvider for camera captures, deleted after reading);
  exam rows via `DocActions`; route `exam/{examId}/photo/{docType}` in `ScanFitApp`; DataStore default for show-unverified.
- **iOS:** Imaging `CropAdjust`, `PersonMask`, `Raster.cgImage`, public inits for `FitResult`/`PipelineResult`; ScanVision
  `VisionFaceDetector`, `VisionPersonSegmenter` (CPU on the simulator); ScanModel `PhotoRoute`; Features `PhotoFlow` target
  (`PhotoFlowViewModel`, `PhotoTools`/`LivePhotoTools`, `PhotoFlowView`, `PhotoCropView`, `PhotoReviewView`, `CameraPicker`) +
  `PhotoFlowTests`; `ExamView(openable:)`; `RootView` path-based navigation; `project.yml` (PhotoFlow dep, `NSCameraUsageDescription`).
- **Removed:** Roborazzi and swift-snapshot-testing (code, baselines, Gradle wiring, CI steps, docs).
- **Docs:** CLAUDE.md (Definition of done, low-end profile), ROADMAP, TESTING, TECH_FEASIBILITY, ARCHITECTURE, UI_UX, BUILD_AND_RELEASE.

## 5. Next steps (in order)

0. **Launch plan first:** follow `docs/LAUNCH_PLAN.md` week by week. Week 0 (by Sun 25 Oct): commit, check the name,
   pick the permanent ids, merchant/agreements setup, keys, Firebase + RevenueCat projects, recruit 15+ testers, rename
   to Mr Doc, first `-rc1` builds. **Closed test must start by Tue 27 Oct.** The device checks below feed it.

1. **Waiting on the user** (LAUNCH_PLAN week 0; the click-by-click version is `docs/OWNER_STEPS.md`, written
   2026-10-20 at the user's request): switch on GitHub Pages (Settings › Pages › Source: GitHub Actions; ids, seller,
   email, city and links are done), sign the presets with the production seed (`build_all.sh --release
   --allow-todo-hi`), the RevenueCat project + API keys and the Firebase
   project (`google-services.json`, `GoogleService-Info.plist`). With keys: RevenueCat behind `Billing` (`logIn(uid)`,
   offline entitlement), Firebase Auth + the `users/{uid}` count (`SaveGate` `merge`), Remote Config `free_saves_total`,
   Analytics honouring `analyticsEnabled` (`free_saves_spent`, drop `rewarded_completed`), Crashlytics.
   **Free saves / paywall / Settings QA (new):** on a debug build, save 20 files (or lower `QuotaConfig` locally), check
   "N free saves left" from 5, the 21st Save/Share/Save all opens the paywall and the review stays; buy a fake product →
   thanks → Save works; Settings rows and links; Hindi at the largest font (paywall cards stack). Android visual check.
   _Now covered on the iPhone 11 Pro Max / iOS 26.5 simulator:_ Settings/paywall smoke (4 native UI tests, TESTING §2),
   including fake purchase/Restore/unavailable-store Retry and Hindi large text. The count is seeded, not 20 physical
   exports. Still do the real export/provider journey above, Android native UI, screen readers and minimum-OS checks.
   Confirm the Done fix with the user's device details (§3).
2. **Device export smoke:** save a real photo on Android API 29+ and API 26–28, and iOS Files/iCloud; cancel and retry; Done
   returns to the exam with Saved, including after app relaunch. Upload the output via `web/upload-test/` to verify byte fidelity.
   iOS lets the user choose the destination folder; `ScanFit/<Exam>/` cannot be forced by the system Files picker.
3. **Ink flow checks:** hand-check the Android ink flow (signature, thumb, declaration; free-crop drag, darker ink slider,
   handwriting tick, save, match note sheet). The emulator froze again on 2026-10-05 (§6): needs the user's device or a better AVD. Time cleanup on a real low-end device (≈20 s in a simulator **debug** build — measure
   release before optimising). Ask a native speaker to review the `TODO_HI:` strings.
4. **Review device QA:** check Before/After, hold/release/cancellation, Hindi at 200% text and TalkBack/VoiceOver on both
   flows. Comparison must preserve Saved and exported bytes. iOS 17 / Android API 29 profiles remain unavailable locally.
5. **Draft device QA:** make a photo/signature without exporting, return to the exam (Ready), relaunch, reopen the retained
   review, save, and confirm identical bytes + Saved. Check Replace/cancel and storage failure; a failed replacement must
   keep the previous good draft. Restored signatures must still require the one-time handwriting confirmation.
6. **Custom size device QA:** Android form + flows on a phone (the emulator cannot run); cm input with a decimal keypad
   in Hindi; recent sizes after relaunch; a size with no dimensions (keep the picture's size).
7. **Thumb with a real print:** the user's own thumb photo through IBPS and a Custom size; compare Before/After.
8. **Share / Save all device QA:** Android 10+ Save all + Open folder; Android share to WhatsApp/Drive; iOS "Keep both"
   in a folder that already has the files; a partial failure (e.g. full storage) and Retry.
9. **Checker device QA:** Android end to end on a phone (Files picker with a PDF, CreateDocument on Android 9, share); iOS
   with a HEIC from Photos and a file from iCloud Drive; Hindi at the largest font (chips wrap).
10. **My Kit device QA:** Save to My Kit from a photo and a signature review, relaunch, Originals lists them with thumbnails;
   "Use for exam…" into another exam (photo face check runs; ink opens at the crop); Done returns to the original; Delete;
   a full Kit (30). History: save, Save all, Checker save and Custom size each add a row; Share from history; Clear history.
   Hindi at the largest font (segmented labels, history rows). iOS simulator walk done (§1); Android and real devices not yet.
11. **Home + Kit previews QA (new):** Android Home on a phone (grid at default and 200 % text, Hindi; every card opens the
   right screen; Back returns to Home), Exam forms search; Kit: thumbnails, tap a History photo and a History PDF (pages
   scroll, Share), Save to My Kit dialog. iOS: the Kit walk with real files on the iPhone 11 Pro Max simulator.
12. **PDF tools device QA:** Android on a phone first (Robolectric cannot run `PdfRenderer`, so the real renderer is
   untested): compress a real certificate PDF (also one with `/Rotate`, one password-protected), merge two PDFs, images →
   PDF with crop/rotate, ID card front/back, JEE Main class 10 row → Save → row Saved → History share. iOS the same on the
   iPhone 11 Pro Max simulator and a device. Time a 3-page compress on the low-end profiles (the iOS debug job suite takes
   ~90 s on a Mac; measure release before optimising). Hindi at the largest font (add buttons wrap).
13. Next features: Scan entry, guide sheet (Phase 3). Verify UPSC ESE / CMS presets
   only with official sources and user direction.

## 6. Testing tips that cost time to learn

- iOS tests that share (photo/ink `share()`, `ShareStager.stage`) must use `ownShareFolder()` (set `model.shareFolder`
  or pass `in:`): suites run in parallel and every stage empties its folder, which made CI flaky (2026-10-20).
- CI step logs need a GitHub sign-in; the unauthenticated API still gives job/step status and annotations:
  `curl -s https://api.github.com/repos/imroshan12/scan-fit/actions/runs?per_page=5`, then `…/runs/<id>/jobs` and
  `…/check-runs/<job id>/annotations`. Reproduce the failing step locally with the command in the workflow file.

- No fixture or built-in macOS image contains a face. To reach crop/review on the simulator, build a **throwaway** copy with a stub
  `FaceDetector` (a box in the middle of any photo) injected in `RootView`, test, then restore the file and reinstall the real build.
  Never leave the stub in the source.
- Ink flows need no face: add a fixture to the simulator's Photos with
  `xcrun simctl addmedia <udid> spec/fixtures/images/signature_paper_shadow.jpg`, then pick it from the gallery.
- The iOS 26 Files export sheet has no Cancel: swipe it down from the top (swipe 160→850 pt) to test a dismissal.
- SwiftUI toggles/checkboxes on the simulator often ignore quick synthetic taps; tap with `duration: 0.15–0.2`. Not an app bug.
- The Files save picker on the simulator writes to "On My iPhone" inside the simulator's data container; inspect the file there.
- `cases.json` may be reformatted (Prettier, tabs) at any time; the writers use `replace_json_array`, so rerunning them is safe.
- Synthetic test images for ink cleanup: write them with PIL in the scratchpad (`.venv/bin/python`); a throwaway Swift test
  can run the real pipeline and write PNGs — delete it afterwards. The thumb cases in `cases.json` are parametric, not files.
- The iPhone 11 Pro Max simulator may be shut down after `xcodebuild test`; `xcrun simctl boot <udid>` before `addmedia`.
- After moving a Kotlin type between modules, "Incremental compilation failed" can appear; rerun once with `--rerun-tasks`.
- Vision fails on the simulator ("Could not create inference context") unless requests run on the CPU — already handled.
- The Android emulator (`Medium_Phone_API_35`, single core) froze with `-memory 4096` ("System UI isn't responding", adb hung),
  and again on 2026-10-05 with its default memory right after installing the app. Don't sink time into it; ask the user.
- iOS simulator taps sometimes register late (a screenshot shows the old screen, the next one the new). Take two screenshots
  before deciding a tap failed, or a delayed tap lands on the next screen (it opened Photograph instead of Signature once).
- Don't run Gradle and `xcodebuild` at the same time; VS Code's Java extension runs its own Gradle on `android/` (stop with
  `./gradlew --stop` if builds hang).
- detekt allows 11 functions per file and 120-char lines; ktlint wants `_uiState`/`uiState` backing-property names; Android lint wants
  `toUri()` and plurals for "N kilobytes"-style strings (a plural may only have one int placeholder; pass others as `str`).
- Android lint rejects three-dot ellipses: use `\u2026` in shared JSON strings. Run SwiftLint from `ios/` to load its configuration
  and exclude generated build files. VS Code's test discovery does not expose the native suites; use Gradle/SwiftPM/Xcode.
- Presets are not signed (since 2026-10-20): `spec/tools/build_all.sh` (or `build_presets.py`) is all a preset change needs
  before the app tests; `make_presets_load_cases.py --check` keeps the shared format-check cases honest.
