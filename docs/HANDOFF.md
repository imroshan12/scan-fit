# HANDOFF.md — where work stopped (read this first when picking the project up)

Last updated: **2026-10-06**. Current phase: **Phase 2 — Core flows** (docs/ROADMAP.md). Latest commit: `cabb530 Phase 2: Signature flow`.
Match note, review comparison/chips, retained drafts/Ready rows, signature preservation and My Kit browsing remain
**uncommitted** in the working tree.
The user commits; a session never runs `git add/commit/push`.

## 1. State in one screen

| Area                                         | State                                                                                                                                                                                                                                                                                          |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Home (search, categories, My exams, Popular) | Done on both apps (ROADMAP Phase 2, ticked).                                                                                                                                                                                                                                                   |
| Exam checklist                               | Photo/ink rows reopen retained files. Verified private drafts show "Ready · KB" after relaunch; historical Saved takes precedence. Missing/invalid/expired drafts never show Ready. Broader PDF/live-photo flows remain open.                                                                  |
| Photo flow                                   | Built on both apps, including save, retry, cancellation and Done after success. Physical-device camera/navigation confirmation remains open (§3).                                                                                                                                              |
| Export / save                                | Single-file save + verify-after-write plus private 30-day retained final drafts (2026-10-06), shared by photo/ink. Restored reviews re-save identical bytes. Save all, share, Open folder, cleaned originals and export history remain open.                                                   |
| Ink flows                                    | Built on both apps (2026-10-05): signature, triple signature, thumbs, NEET fingers, declaration. Free crop, cleanup variant by type, crisp black / darker ink, one-time handwriting tick, save + verify. Verified end to end on the iPhone 11 Pro Max simulator; Android hand check open (§5). |
| Review                                       | Built on both apps: match note (2026-10-05), Before/After + hold-to-compare and output-derived KB/dimensions/JPG chips (2026-10-06). No Fix button in exam flows (decision, §2). Gesture/accessibility device QA remains open.                                                                 |
| My Kit, Custom, Checker                      | Not started.                                                                                                                                                                                                                                                                                   |
| Screenshot tests                             | Removed for good (user decision 2026-10-03). Don't add them back (CLAUDE.md Definition of done).                                                                                                                                                                                               |

Gates last run (2026-10-06): Android `./gradlew spotlessCheck detekt testDebugUnitTest :core:match:test lint :app:assembleDebug`
green (315 unit tests). iOS: strict SwiftLint **from ios/**, full Core (166 tests) and Features (90 tests) SwiftPM regressions, DesignSystemTests
(11 including 4 decode-lifecycle checks), and iPhone 17 simulator app build. Spec: 57 tool tests, `gen_strings.py --check`,
debug preset build/sign/verify. Both platforms consume all eight `export_cases`, 18 `crop_cases`, 6 `match_note_cases` and
8 `review_check_cases` and all 11 `draft_cases`. Draft tests also cover actual file relaunch, atomic replacement failures,
symlinks/size limits, cancellation, current/retired presets, Ready/Saved precedence and restored Files save callback ordering.
All 3 `ink_preservation_cases` pass on both platforms, with native UPSC ESE triple-signature fit/encode regressions.
Engine coverage refreshed: Android inspect 95.1%, match 97.8%, imaging 98.8%; iOS Inspect 96.7%, Match 96.6%, Imaging 97.8%.
Device/provider QA remains open. New `review.*`, `draft.*` and `kit.*` Hindi messages are
drafted with `TODO_HI:`. The spec suite still emits pre-existing unclosed-file `ResourceWarning`s in `exam_search.py`.

## 2. Decisions made with the user (not obvious from the code)

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
- UPSC ESE / CMS are low-confidence presets ("assumed common UPSC rules"). Offered to the user, not started: verify them against the
  official notices, and/or a search hint when a query only matches hidden exams.

## 3. Device reports and remaining confirmation

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

1. Confirm the Done fix with the user's device details (§3).
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
6. Finish wider export actions: sharing retained bytes, Save all with partial-failure handling, Open folder, then My Kit
   cleaned originals/export history, Custom and Checker (reuse `MatchNote`; add Fix there). Verify UPSC ESE / CMS presets
   only with official sources and user direction.

## 6. Testing tips that cost time to learn

- No fixture or built-in macOS image contains a face. To reach crop/review on the simulator, build a **throwaway** copy with a stub
  `FaceDetector` (a box in the middle of any photo) injected in `RootView`, test, then restore the file and reinstall the real build.
  Never leave the stub in the source.
- Ink flows need no face: add a fixture to the simulator's Photos with
  `xcrun simctl addmedia <udid> spec/fixtures/images/signature_paper_shadow.jpg`, then pick it from the gallery.
- SwiftUI toggles/checkboxes on the simulator often ignore quick synthetic taps; tap with `duration: 0.15–0.2`. Not an app bug.
- The Files save picker on the simulator writes to "On My iPhone" inside the simulator's data container; inspect the file there.
- `cases.json` may be reformatted (Prettier, tabs) at any time; the writers use `replace_json_array`, so rerunning them is safe.
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
- `build_presets.py` without signing rewrites the bundle and can leave a stale signature. For local debug validation, run
  `.venv/bin/python spec/tools/build_presets.py --sign-dev` then `--verify spec/signing/dev_public_key.b64` before preset tests
  (or `PYTHON="$PWD/.venv/bin/python" spec/tools/build_all.sh`). Never use release signing for a local debug check.
