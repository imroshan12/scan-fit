# ALGORITHMS.md — behaviour contract for both platforms

Both apps implement everything here independently, in Kotlin and in Swift. Outputs do not need to
be byte-identical, but they **must** pass every assertion in `fixtures/cases.json`. When
behaviour changes, update this file and the cases first.

Terms: **KB = 1024 bytes** (portals use 1024; verify with the upload echo page, TESTING §5).
`window = [min, max]` from the preset, with `min = 0` when null. `T` = preset `target`.

---

## 1. Image fit engine (`fit(image, docSpec) → FitResult`)

### 1.1 Decode
1. Read the EXIF orientation and apply it to the pixels. The output never carries an orientation tag.
2. Downsample on decode so the long side is ≤ `max(2 × largest target dimension, 1600px)`.
   Android: `ImageDecoder` with `setTargetSize`, or `BitmapFactory.Options.inSampleSize`, then scale.
   iOS: `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceThumbnailMaxPixelSize`.
   Never create a full-resolution 50 MP bitmap.
3. Convert to 8-bit sRGB. Flatten alpha onto white.

### 1.2 Geometry by `dimensions.mode`
| Mode | Crop / fit | Output size |
|---|---|---|
| `exact` | Crop to exactly the target aspect ratio (user can adjust the crop; locked ratio) | exactly `width×height` |
| `preferred` | Crop to target aspect | `width×height` initially; may upscale in §1.4 keeping aspect |
| `range` | Crop to an aspect within `aspect_w_over_h` (default to the midpoint) | the largest size within `[min,max]` box that the source supports, starting at the midpoint box |
| `none` | User crop, free ratio | long side 1200px (photo), 1000px (signature/thumb), 1600px (documents) |

Photos are **cropped** to the aspect ratio. Signatures, thumbs and declarations are **padded with white**
to the aspect ratio after trimming to the ink (§3), and are never stretched or cropped through ink.
Resampling uses a high-quality filter (Android: `Bitmap.createScaledBitmap(filter=true)` in ≤2×
steps for large reductions. iOS: Core Image `CILanczosScaleTransform` or vImage).

### 1.3 Size search (hitting the window from above)
```
margin  = max(1 KB, 5% of (max - min))       // stay away from the edges
goal    = [min + margin, max - margin]
q range = [35, 95] (integer; iOS uses q/100)
binary search q for the encoded size closest to T with size ∈ goal   (≤ 8 encodes)
if size(q=35) > goal.hi:
    if mode ∈ {none, range}: scale dimensions by 0.85 and repeat, down to the mode's minimum
        (range: min box; none: long side ≥ 600px photo / 400px signature)
    else (exact/preferred): FAIL(TOO_DETAILED) → UI suggests plainer background / re-crop
```

### 1.4 Minimum-size strategy (hitting the window from below)
This is common: a clean 140×60 signature is about 6 KB at q=100 and a 200×230 photo is about 16 KB
at q=95, but IBPS needs ≥10 KB and ≥20 KB. When `size(q=100) < goal.lo`:

1. **Upscale** (modes `preferred`, `range`, `none` only): multiply dimensions by 1.25 per step,
   keeping the aspect exactly, up to:
   - `preferred`: 2 × preferred size
   - `range`: the max box
   - `none`: long side 2000px

   Re-run §1.3 at each step. Upscaling adds real pixels, which matches the intent of a minimum-quality rule.
2. **Pad** (always the last resort; the only option for `exact`): insert JPEG `COM` segments
   (marker `FF FE`, ≤ 65,533 payload bytes each, ASCII space `0x20` payload) right after APP0,
   until the size reaches `T` (or `goal.lo` when T is above what padding makes sensible). The
   pixels are unchanged and every decoder ignores COM.
3. Remote Config `min_fill_strategy` switches between `upscale_then_pad` (default) and `pad_only`,
   in case a portal is found to validate exact pixels.

Record which strategy was used in `FitResult.strategy` (analytics enum only).

### 1.5 Byte post-processing (`JpegPatcher`, both platforms)
Run on the final bytes:
1. Must start with SOI `FFD8`. Walk segments until SOS.
2. Remove APP1 (EXIF/XMP), APP13 (Photoshop/IPTC) and any APPn other than APP0 and APP14.
   Remove APP2 (ICC) **only** if the profile is sRGB. Otherwise convert to sRGB first.
3. Make sure APP0 JFIF exists with `units = 1 (dpi)` and `Xdensity = Ydensity = spec.dpi ?? 200`.
   Insert it if missing. Patch it in place if present.
4. Assert the frame is **SOF0 (baseline)**. SOF2 (progressive) → re-encode (Android `Bitmap.compress`
   and iOS ImageIO both produce baseline by default; this guards regressions).
5. Assert 3 components (YCbCr) or 1 (grayscale, allowed for signature/thumb/declaration only if
   remote flag `allow_grayscale_docs`). 4 components = CMYK = fail.

### 1.6 Verify after write
After writing to the destination (app storage, MediaStore or Files):
re-open the **written** file, run the Inspector (§5) and assert format, size ∈ window, dimensions
per mode, baseline and RGB. On failure: delete the file and show the error with a retry. Never show
a success tick without this check.

### 1.7 Export naming
`<filename or docType>_<examShort>_<WxH>_<KB>kb.jpg`, e.g. `signature_IBPS-PO_140x60_16kb.jpg`.
If the preset has `filename` (NTA: `Photograph`, `Signature`), use exactly `<filename>.jpg`, and put
files in a per-exam folder so names don't collide:
Android `Downloads/ScanFit/<Exam>/`, iOS `Files › ScanFit › <Exam>/`.

---

## 2. Photo pipeline
1. **Face detection** (Android ML Kit Face Detection, bundled model; iOS Vision
   `VNDetectFaceLandmarksRequest`). 0 faces → block with a message. >1 face → ask to re-crop.
2. **Auto-framing**: the face box height (chin to hairline estimate = face box × 1.25) should be
   60–75% of the output height, horizontally centred, with the top of the head 8–12% from the top.
   Presets may override this later with a `face_coverage` field (UPSC: about 75%; SSC live: about 80%).
   The user can adjust; the crop stays aspect-locked.
3. **Background whitening** (optional toggle, off by default): person segmentation (Android ML Kit
   Selfie Segmentation; iOS `VNGeneratePersonSegmentationRequest`, quality `.accurate`). Feather the
   mask 2–3px, composite onto `#FFFFFF`. Never alter pixels inside the mask. Show before/after.
4. **Name/date strip** (when the preset has `name_date_strip.required`, or the user adds one): a white
   strip at the bottom equal to **18%** of the output height, added *inside* the target dimensions
   (the photo area is shrunk, not stretched). Two centred lines: NAME (uppercase, bold) and
   `DD/MM/YYYY`. Black text, font size = 38% of strip height for the name and 32% for the date.
   Auto-shrink the name down to 60% of that size before truncating with an ellipsis. Use the
   system sans font (Roboto / SF Pro).
5. Fit (§1).

## 3. Ink document cleanup (signature, thumb, declaration, triple signature)
1. **Rectify**: use the corners from the document scanner, or a manual 4-corner crop, then perspective warp.
2. **Luminance** L = 0.299R + 0.587G + 0.114B.
3. **Background normalisation**: `bg = gaussianBlur(L, σ = shortSide/30)`; `N = clamp(L / bg × 255)`.
   This removes phone shadows and paper tint.
4. **Ink mask** (signature, declaration): Sauvola threshold on N, window = odd(shortSide/20),
   k = 0.34, R = 128. Morphological open (3×3) to drop specks smaller than 0.02% of the area.
   **Thumb**: no binarisation (ridges matter). Apply CLAHE-like contrast stretch (clip at the 2nd/98th
   percentile) on N, then gamma 0.8.
5. **Render**: background = pure white. Ink colour = the original pixel darkened ×0.6 (keeps blue or
   black), or pure black when "Crisp black" is on (default for signatures).
6. **Trim**: bounding box of the ink + padding of 8% of the box's long side. Thumb: square crop
   centred on the ink centroid.
7. **Fit to aspect** by padding with white (§1.2), then §1.
8. **Quality gate**: ink coverage must be 0.5–35% of the area; otherwise warn "too faint" or
   "too dark / not on white paper".

Do **not** try to detect CAPITAL-letter signatures. It isn't reliable from images. Show a
one-time checkbox instead: "I signed in running handwriting, not CAPITAL letters".

## 4. Match engine — "which exams accept this file?" (feature: exam note on every edit)

Input: an `InspectedFile` (format, bytes, width, height, colour, progressive, dpi) plus `docKind`
(`photo | signature | thumb | declaration | fingers | pdf_document`). The kind comes from the flow
that produced the file, or a user pick in the Checker.

Kind → preset document types:
`photo → photo, postcard_photo` · `signature → signature, triple_signature` ·
`thumb → left_thumb, thumb_impression` · `declaration → handwritten_declaration` ·
`fingers → left/right_hand_fingers_thumb` · `pdf_document → any PDF-format type`.

For every active exam and matching slot, evaluate the hard constraints:

| Constraint | Pass rule |
|---|---|
| format | file format ∈ `formats` (jpg ≡ jpeg) |
| size | `min ≤ KB ≤ max` (a null max is treated as unknown → at best ACCEPTED) |
| colour / encoding | RGB (or allowed gray), baseline |
| dims `exact` | equal to ±0 px |
| dims `preferred` | aspect within ±3% of preferred (size may differ) |
| dims `range` | inside the box and the aspect range |
| live-photo exams | exams whose photo is captured live have no photo slot → never listed for `photo` |

Result per exam:
- **EXACT**: all pass, and preferred dims match within ±2 px (or the mode is exact/range).
- **ACCEPTED**: all pass, but preferred dims differ, or max is null.
- **NEAR_MISS**: exactly one constraint fails *and* it's fixable in one tap: size outside the window
  by ≤ 50% of the window width, a wrong but convertible format, or progressive/CMYK. Carries a
  `fix` action (`compress_to_target`, `enlarge_to_target`, `convert_to_jpeg`, `reencode_baseline`).
- **NO**: otherwise.

Low-confidence exams are never shown as EXACT. Show them as "likely OK" with the Unverified badge,
and never count them in the headline number.
A slot with **no size limits and dimensions mode `none`** (an unknown spec, e.g. BPSC today) is
`UNKNOWN`: never listed as accepting a file. The exam screen shows "Spec not verified yet".

**UI contract** (shown after every export, every custom resize — updated live as sliders move,
throttled to 150 ms — and in the Checker):
```
✓ Accepted by 14 exams     IBPS PO · SBI PO · RBI Grade B  +11  ›
⚠ 1 quick fix              SSC CGL signature needs ≤ 20 KB   [Fix]
```
Group by body in the detail sheet. Sort: EXACT before ACCEPTED, then by exam popularity (remote
config list), then name. Tapping Fix runs §1 against that slot and re-runs the match.

## 5. Inspector (`inspect(uri) → InspectedFile + issues[]`)
Detect the format from **magic bytes**, not the extension: JPEG `FFD8FF`, PNG `89504E47`, PDF `%PDF-`,
HEIC `ftypheic|ftypheix|ftypmif1`, WebP `RIFF....WEBP`.
- JPEG: walk the markers. SOF0/1 = baseline, SOF2 = progressive. Components = 4 → CMYK
  (confirm with APP14 Adobe). Read APP0 density. Read EXIF orientation (a rotated display means the
  portal may show it sideways).
- PNG: IHDR dims and colour type. PDF: page count, encrypted flag, and whether pages are image-only
  (heuristic: no text objects).

Issue enum: `EXTENSION_MISMATCH, CMYK_COLOR, PROGRESSIVE_JPEG, HAS_GPS_EXIF, ROTATED_BY_EXIF,
HEIC_NOT_ACCEPTED, PDF_ENCRYPTED, TOO_SMALL_KB, TOO_LARGE_KB, WRONG_DIMENSIONS, WRONG_ASPECT,
LOW_DPI_METADATA, GRAYSCALE_NOT_ALLOWED`. Each has an EN/HI message and an optional fix action.

## 6. PDF engine
**Compress to ≤ X KB**: for each page, rasterize at a DPI ladder `[200, 150, 120, 100]`. At each
DPI, binary-search JPEG q in `[40, 85]` for the whole-document size ≤ `X − 3%`. Pick the highest DPI
that fits. A "Grayscale" toggle (default on for certificates) roughly halves the size. Below 100 DPI
→ FAIL(CANNOT_REACH) with a suggestion: split pages, or crop margins.
Note in the UI: text becomes image (not selectable). That's fine for portal uploads.

**Images → PDF**: A4 portrait (595×842 pt), image fitted with 5% margins, and each image first run
through §3 document cleanup (optional toggle). **ID card mode**: front and back of a card on one A4
page, each 85.6×54 mm at true scale, stacked with a 15 mm gap.
**Merge**: page-level copy. Android `PdfRenderer` → `PdfDocument`; iOS `PDFDocument.insert`.
Encrypted input → show "Remove the password first" (v1).

## 7. Live photo coach (practice only, nothing is uploaded)
Real-time on camera frames at ≥10 fps analysed, preview at 30 fps:

| Check | Rule (pass) | Source |
|---|---|---|
| One face | exactly 1 | face detector |
| Size | face box height 45–60% of frame height (≈ 80% of the portal oval) | box |
| Frontal | \|yaw\| < 10°, \|roll\| < 8°, \|pitch\| < 12° | Android Euler angles / iOS `VNFaceObservation.yaw/roll/pitch` |
| Eyes open | both eye-open probability > 0.6 (Android); eye aspect ratio from landmarks > 0.2 (iOS) | |
| Lighting | face mean luminance 90–200; left/right half difference < 25 | pixels |
| Background | border region (outer 12%) luminance std-dev < 22 and mean > 150 | pixels |
| Sharp | variance of the Laplacian on the face crop > threshold calibrated per device class (start: 60) | pixels |
| iOS extra | `VNDetectFaceCaptureQualityRequest` ≥ 0.5 | Vision |
| Glasses / cap | **not auto-detected** (no reliable on-device API). Show a reminder chip | — |

All checks green for 1 second → haptic, then "Looks good — do the same on the portal". Thresholds
live in Remote Config (`coach_thresholds`) so they can be tuned without a release.

## 8. Conformance
`fixtures/cases.json` is run by both platforms' unit tests (Android runs Robolectric with real Skia
encoding via `robolectric` native graphics mode, or on an instrumented device job; iOS runs it
directly). A PR that changes engine behaviour and breaks a case must update the case **and** explain
why in the PR description.
