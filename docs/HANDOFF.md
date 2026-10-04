# HANDOFF.md — where work stopped (read this first when picking the project up)

Last updated: **2026-10-04**. Current phase: **Phase 2 — Core flows** (docs/ROADMAP.md). Everything below is in the working tree
but **not committed** (the last commit is `90fd134 Phase 2 start`). The user commits; a session never runs `git add/commit/push`.

## 1. State in one screen

| Area                                         | State                                                                                                                                                                                           |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Home (search, categories, My exams, Popular) | Done on both apps (ROADMAP Phase 2, ticked).                                                                                                                                                    |
| Exam checklist                               | Verified photo exports persist the row status "Saved" on both apps. Photo rows are tappable (chevron). Intermediate "Ready" rows remain a follow-up.                                            |
| Photo flow                                   | Built on both apps, including save, retry, cancellation and Done after success. Physical-device camera/navigation confirmation remains open (§3).                                               |
| Export / save                                | Single-photo save + verify-after-write done on both apps. Android Downloads (SAF below API 29), iOS Files picker. Save all, share, Open folder, retained copies and export history remain open. |
| Ink flows, My Kit, Custom, Checker           | Not started.                                                                                                                                                                                    |
| Screenshot tests                             | Removed for good (user decision 2026-10-03). Don't add them back (CLAUDE.md Definition of done).                                                                                                |

Gates last run (2026-10-04): Android `./gradlew spotlessCheck detekt testDebugUnitTest lint :app:assembleDebug` green;
iOS `swiftlint --strict` **from ios/**, `swift test` Core (`--skip DesignSystemTests`) and Features (45 tests, including 33
photo/export tests), app tests on the iPhone 17 simulator; spec 56 tool tests, all 55 presets valid, dev signature verified,
`gen_strings.py --check`. Both platforms consume all eight shared `export_cases`. Device/provider QA and new coverage measurements
remain open. Hindi export messages are drafted with `TODO_HI:` for native review.

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
- UPSC ESE / CMS are low-confidence presets ("assumed common UPSC rules"). Offered to the user, not started: verify them against the
  official notices, and/or a search hint when a query only matches hidden exams.

## 3. Open issue: "Done does nothing" on the user's physical device

- Not reproducible on the simulator: Done pops back to the exam from Popular, from search, and after cancelling the Files picker.
- Untested path: the **camera** (full-screen cover over the flow) — the likely trigger for `@Environment(\.dismiss)` misbehaving.
- Fix applied (unconfirmed on device): iOS no longer uses `dismiss()` in the flow. `RootView` owns `NavigationStack(path: $homePath)`
  and passes `PhotoFlowView(onExit:)`, which removes the last path element. Android: Done = `navController.popBackStack()`, and the
  photo route now uses `launchSingleTop` so a double tap cannot stack two flows.
- Asked the user for: iPhone or Android + OS version, photo source (camera/gallery/Files), and what happens on Done. Follow up there.
- Review now saves and verifies before offering Done; Saved persists on the exam checklist. This removes the former no-save
  behavior, but does not substitute for confirming the camera/navigation fix on the user's physical device.

## 4. What changed this session (by area, for review)

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
3. **Ink flows** (signature, thumb, declaration): reuse the verified export contract; keep doc-kind matching and grayscale rules
   correct rather than calling the current photo-only verifier unchanged.
4. Finish the wider export/review items: Ready checklist rows, Save all, sharing, Open folder, My Kit/export history, match note
   and before/after. Verify UPSC ESE / CMS presets only with official sources and user direction.

## 6. Testing tips that cost time to learn

- No fixture or built-in macOS image contains a face. To reach crop/review on the simulator, build a **throwaway** copy with a stub
  `FaceDetector` (a box in the middle of any photo) injected in `RootView`, test, then restore the file and reinstall the real build.
  Never leave the stub in the source.
- Vision fails on the simulator ("Could not create inference context") unless requests run on the CPU — already handled.
- The Android emulator (`Medium_Phone_API_35`, single core) froze with `-memory 4096` ("System UI isn't responding", adb hung).
- Don't run Gradle and `xcodebuild` at the same time; VS Code's Java extension runs its own Gradle on `android/` (stop with
  `./gradlew --stop` if builds hang).
- detekt allows 11 functions per file and 120-char lines; ktlint wants `_uiState`/`uiState` backing-property names; Android lint wants
  `toUri()` and plurals for "N kilobytes"-style strings (a plural may only have one int placeholder; pass others as `str`).
- Android lint rejects three-dot ellipses: use `\u2026` in shared JSON strings. Run SwiftLint from `ios/` to load its configuration
  and exclude generated build files. VS Code's test discovery does not expose the native suites; use Gradle/SwiftPM/Xcode.
- `build_presets.py` without signing rewrites the bundle and can leave a stale signature. For local debug validation, run
  `.venv/bin/python spec/tools/build_presets.py --sign-dev` then `--verify spec/signing/dev_public_key.b64` before preset tests
  (or `PYTHON="$PWD/.venv/bin/python" spec/tools/build_all.sh`). Never use release signing for a local debug check.
