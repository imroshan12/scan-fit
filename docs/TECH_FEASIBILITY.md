# TECH_FEASIBILITY.md — tools, APIs and what's actually possible

Rating: 🟢 proven platform API, low risk · 🟡 works with caveats or tuning · 🔴 not reliably
possible, so design around it. "Spike" = time-boxed proof in Phase 0/1 before building on it.

## 1. Feature × tool matrix

| Feature | Android | iOS | Rating | Caveats / decisions |
|---|---|---|---|---|
| Decode + downsample, EXIF rotate | `ImageDecoder` (API 28+), `BitmapFactory` fallback, `ExifInterface` (androidx) | ImageIO `CGImageSourceCreateThumbnailAtIndex` (applies orientation with `kCGImageSourceCreateThumbnailWithTransform`) | 🟢 | Handle HEIC input: Android 9+ decodes HEIF; iOS native |
| JPEG encode with quality | `Bitmap.compress(JPEG, q)` (baseline, libjpeg-turbo via Skia) | `CGImageDestination` + `kCGImageDestinationLossyCompressionQuality` | 🟢 | Neither exposes chroma subsampling, so the engine doesn't rely on it (ALGORITHMS §1.4) |
| DPI metadata | No API → `JpegPatcher` byte patch of APP0 | ImageIO `kCGImagePropertyDPIWidth/Height` **or** the same patcher | 🟢 | Use the patcher on both for one code path per spec |
| Strip EXIF/GPS | Re-encoding from a Bitmap drops EXIF; patcher asserts it | ImageIO copies metadata only if asked; patcher asserts | 🟢 | |
| Minimum-KB padding (COM segments) | byte ops | byte ops | 🟢 | Spike: upload padded files to the echo page + 2 real portals' client-side validators |
| Face detection + landmarks + angles | ML Kit Face Detection (**bundled** model, offline): contours, Euler X/Y/Z, eye-open probability | Vision `VNDetectFaceLandmarksRequest` (yaw/roll/pitch), `VNDetectFaceCaptureQualityRequest` | 🟢 | iOS has no eye-open probability → eye aspect ratio from landmarks |
| White background | ML Kit Selfie Segmentation (on-device, ~few MB) | `VNGeneratePersonSegmentationRequest` (iOS 15+) | 🟡 | Hair edges need feathering; off by default, before/after shown. Spike on 20 real photos |
| Glasses / cap detection | none reliable | none reliable | 🔴 | Reminder chips instead. A custom TFLite/Core ML classifier is a v2 option |
| CAPITAL-letter signature detection | ML Kit Digital Ink is stroke-based (not images); text OCR is poor on signatures | Vision OCR is the same | 🔴 | Checkbox confirmation (ALGORITHMS §3) |
| Ink cleanup (normalise, Sauvola, morph) | Hand-written Kotlin over `IntArray` on ≤1600px images (≈10–40 ms) | vImage / Accelerate, or Core Image kernels | 🟢 | **No OpenCV**: +20 MB per ABI for a few filters isn't worth it |
| Document scanning UI | ML Kit Document Scanner (Play services; no camera permission; auto-capture, crop, cleanup) | VisionKit `VNDocumentCameraViewController` | 🟢/🟡 | Android: needs Play services and **≥1.7 GB RAM**, otherwise returns UNSUPPORTED → fall back to CameraX capture + manual 4-corner crop (build this anyway for signature-on-paper) |
| Live camera analysis (coach) | CameraX `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`, 640×480) | `AVCaptureVideoDataOutput` + Vision sequence handler | 🟢 | Throttle to 10–15 fps analysed. Front camera |
| Name/date strip text render | `Canvas` + `StaticLayout` | Core Graphics / `ImageRenderer` | 🟢 | Devanagari names render with system fonts |
| PDF read/rasterize | `PdfRenderer` (framework) | PDFKit `PDFPage.thumbnail`/draw into `CGContext` | 🟢 | Encrypted PDFs: framework can't open → "remove password first" in v1 |
| PDF write / merge | `PdfDocument` (Canvas pages) | `UIGraphicsPDFRenderer`, `PDFDocument.insert` | 🟢 | Output is image-based (fine for portals) |
| PDF password protect (v1.1) | needs a library: **PdfBox-Android** (Apache-2.0) | PDFKit `write(to:withOptions: [.userPasswordOption…])` | 🟡 | Adds ~3 MB on Android; v1.1 only |
| OCR (v1.1 search, Aadhaar mask) | ML Kit Text Recognition v2 (Latin + **Devanagari** bundled models) | Vision `VNRecognizeTextRequest` | 🟡 | **Spike:** confirm Vision's Hindi support on the minimum iOS version; if missing, Hindi search is Android-first |
| Photo picking without permissions | Photo Picker (`PickVisualMedia`, backported via Play services) | `PhotosPicker` (out of process, no permission) | 🟢 | |
| Save to user-visible storage | MediaStore Downloads (API 29+), SAF below | `fileExporter` to Files | 🟢 | See §3 Export spike |
| In-app purchases | Play Billing (via RevenueCat) — UPI supported in India | StoreKit 2 (via RevenueCat) | 🟢 | Keep the RevenueCat SDK current; Play enforces minimum Billing Library versions over time |
| Ads | Google Mobile Ads SDK + UMP | Google Mobile Ads SDK + UMP, SKAdNetwork IDs in Info.plist | 🟢 | Non-personalised only in v1 |
| Remote Config / Analytics / Crashes | Firebase (BoM) | Firebase (SPM) | 🟢 | |
| Presets signature verification | **Tink** (`tink-android`) Ed25519 | CryptoKit `Curve25519.Signing.PublicKey.isValidSignature` | 🟢 | Same key, same bytes; test vector in fixtures |
| Background sync | WorkManager periodic (24h, network constraint) | `BGAppRefreshTask` (best effort) + on-foreground fetch | 🟢 | iOS background timing isn't guaranteed; that's fine |
| Hindi UI | per-app language (`AppCompatDelegate.setApplicationLocales` / `LocaleManager`) | String Catalogs + in-app language via Settings deep link | 🟢 | |
| Push (v1.2 exam alerts) | FCM topics | FCM → APNs | 🟢 | Needs notification permission (Android 13+) — ask in context only |
| On-device LLM (v2) | ML Kit GenAI APIs on supported devices | Foundation Models framework on Apple Intelligence devices | 🟡 | Small device coverage in India; v2 exploration only |

## 2. Dependencies (allow-list — add here before using anything new)
**Android:** AndroidX (core, activity, lifecycle, navigation-compose, room, datastore, work,
camera-camera2/lifecycle/view, exifinterface), Compose BOM + Material 3, Hilt, Kotlinx
serialization + coroutines, Coil 3 (thumbnails only), ML Kit (face-detection bundled,
segmentation-selfie, play-services-mlkit-document-scanner, text-recognition + devanagari in
v1.1), Tink, RevenueCat purchases, play-services-ads + UMP, Firebase BoM (analytics,
crashlytics, config). **Test:** JUnit5 or JUnit4, Turbine, MockK (sparingly; prefer fakes),
Robolectric, Roborazzi, Compose UI test, Macrobenchmark + Baseline Profile.
**iOS:** Swift packages from Apple frameworks only, plus RevenueCat, Google Mobile Ads + UMP,
Firebase (Analytics, Crashlytics, RemoteConfig). **Test:** Swift Testing, XCTest/XCUITest,
pointfreeco `swift-snapshot-testing`.
**Added in Phase 0:** androidx.navigation-compose and androidx.appcompat (per-app language below API 33), androidx.hilt
(navigation-compose), compose material-icons-core (not `-extended`: size), `javax.inject` via Hilt, Tink `tink-android`
(Ed25519 on minSdk 26). **Tooling:** XcodeGen (generates the Xcode project; not shipped in the app), detekt **2.0.0-alpha** (`dev.detekt`;
the stable 1.23 line targets an older Kotlin: revisit when 2.0 is stable).
**Tooling:** ktlint via Spotless, detekt, SwiftLint, SwiftFormat, fastlane, Gradle Play Publisher.
Licence check in CI (no GPL/AGPL in the app binary).

## 3. Spikes (Phase 0–1, each ≤ 1 day, record results in `docs/spikes/<name>.md`)
1. **Export → browser upload fidelity.** Build `web/upload-test/`, a static page that reads a
   chosen file **locally in JS** (no upload) and prints bytes, format, dimensions, SOF type and
   APP0 DPI. Test on Android Chrome (Files, Downloads, Photo Picker paths), Samsung Internet, iOS
   Safari (Files vs **Photos**) and Chrome iOS. Record which paths change the bytes. Expected: some
   iOS Photos paths re-encode → keep Files as the iOS default and add the warning.
2. **Minimum-KB strategies accepted by portals.** Use real portal validators where reachable
   without submitting (many do client-side checks on file select): upscaled vs padded files.
3. **Segmentation quality** on 20 consented real photos (hair, dupatta, glasses) → decide default,
   feather radius, and "don't use if" guidance.
4. **Low-RAM path**: Android Go / 2 GB device. Document scanner availability, decode peak memory
   with 48 MP input, CameraX analysis fps.
5. **Conformance in CI**: confirm Robolectric (native graphics mode) produces real JPEG sizes
   comparable to a device (±10%). If not, run `:core:imaging` conformance as an instrumented test
   on an emulator in CI.
6. **Vision Hindi OCR** (v1.1 prerequisite).

## 4. Direct-billing alternative (if avoiding RevenueCat)
Android: Play Billing Library directly, with local purchase-token acknowledgement + entitlement
cache; no server receipt validation (acceptable risk for ₹99). iOS: StoreKit 2
`Transaction.currentEntitlements` (signed JWS verified on-device by StoreKit). Wrap both behind
the same `EntitlementRepository` so switching later is a single-module change.
