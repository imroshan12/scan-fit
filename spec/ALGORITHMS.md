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
q range = [35, 95] (integer; iOS uses q/100). q = 100 is tried only by §1.4. Exact procedure: §9.4
binary search q for the encoded size closest to T with size ∈ goal   (≤ 8 encodes)
if size(q=35) > goal.hi:
    if mode ∈ {none, range}: scale dimensions by 0.85 and repeat, down to the mode's minimum
        (range: min box; none: long side ≥ 600px photo / 400px signature)
    else (exact/preferred): FAIL(TOO_DETAILED) → UI suggests plainer background / re-crop
```

### 1.4 Minimum-size strategy (hitting the window from below)
(Exact thresholds, ladders and the pad target: §9.4.)
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
Group by body in the detail sheet. Sort: EXACT before ACCEPTED, then by exam popularity (the bundle's
`popular` list, §10; Remote Config `popular_exam_order` may override it from Phase 4), then name. Tapping Fix runs §1 against that slot and re-runs the match.

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

---

## 9. Precise definitions (the conformance contract)

§1–§8 say *what* happens. This section pins every number and edge case that two independent implementations
must agree on. When it disagrees with a sentence above, **this section wins** and the sentence above is a bug.
Phase 1 resolved these ambiguities in the original text: the q range of §1.3 vs §1.4, the pad target, the start
size of `range` mode, what counts as an EXACT match, and how UNKNOWN/low-confidence slots appear in results.

### 9.1 Raster
8-bit sRGB, interleaved R,G,B, row-major, no alpha (alpha was flattened onto white in §1.1).
Luma (integer): `L = (299·R + 587·G + 114·B + 500) / 1000` (integer division).
Resize filter: any high-quality filter (§1.2). Outputs need not match bit-for-bit across platforms.
`round(x) = floor(x + 0.5)`. Integer division floors (all values here are non-negative).
**Default crop** to aspect `a = w/h` from a `W×H` source: if `W/H > a` the crop is `h = H`, `w = round(H·a)`; otherwise `w = W`,
`h = round(W/a)`; position `x = (W−w)/2`, `y = (H−h)/2` (integer division). **Pad to aspect** `a` (ink documents): if `W/H < a` the canvas is
`round(H·a) × H`, else `W × round(W/a)`; the source is centred (`offset = (canvas − source)/2`, integer division) on white.
**Downsample** (§1.1): the decoded long side equals `min(source long side, cap)` exactly (aspect kept, `round` for the short side),
where `cap = max(2 × largest target dimension, 1600)`.

### 9.2 Inspector (`inspect(bytes, fileName?) → InspectedFile`)
Fields: `format` (`jpeg|png|pdf|heic|webp|unknown`, from magic bytes), `bytes`, `kb = bytes / 1024`, and per format:
- JPEG: `width`, `height`, `components`, `color` (`rgb` for 3, `gray` for 1, `cmyk` for 4), `progressive` (any of SOF2/6/10/14),
  `sof` (e.g. `SOF0`), `jfif` (`units`, `xDensity`, `yDensity`, or null), `exifOrientation` (1–8 or null), `hasExif`, `hasGps`,
  `hasIcc`, `hasXmp`, `hasAdobe`.
- PNG: `width`, `height`, `color` (`gray|rgb|indexed|gray_alpha|rgba`).
- PDF: `pages` (count of `/Type /Page` that is not `/Pages`), `encrypted` (`/Encrypt` present), `imageOnly` (no `/Font`).
Context-free `issues`, in this fixed order: `EXTENSION_MISMATCH` (the extension names a known format different from the
detected one; jpg≡jpeg; heif≡heic; unknown extensions never mismatch), `CMYK_COLOR`, `PROGRESSIVE_JPEG`, `HAS_GPS_EXIF`,
`ROTATED_BY_EXIF` (orientation present and ≠ 1), `HEIC_NOT_ACCEPTED`, `PDF_ENCRYPTED`.
Slot-specific issues (`TOO_SMALL_KB … GRAYSCALE_NOT_ALLOWED`) come from the match engine's slot evaluation (§9.7), so the
Checker and the match note can never disagree. Malformed or truncated input never throws: it returns what was readable.

### 9.3 JpegPatcher (`patch(bytes, dpi, allowGrayscale) → bytes | error`)
Output segment order: SOI, **JFIF APP0**, then the remaining kept segments in original order, then the rest of the file.
- Dropped: every APP1 (EXIF, XMP), APP13, every APPn except APP0/APP14, every APP0 that is not JFIF, **every COM**
  (so padding is deterministic), and APP2 ICC **only if the profile is sRGB** (the concatenated ICC data contains the text `sRGB` either as ASCII, as in ICC v2 `desc` tags, or as UTF-16BE `00 73 00 52 00 47 00 42`, as in v4 `mluc` tags).
  A non-sRGB ICC profile is an error `NON_SRGB_PROFILE` (the pipeline must have converted to sRGB before encoding).
- JFIF: always `units = 1`, `Xdensity = Ydensity = dpi` (spec `dpi`, default 200); inserted or rewritten in place (version 1.01).
- APP14 (Adobe) is kept.
- Errors: `NOT_JPEG`, `TRUNCATED` (no SOS), `NOT_BASELINE` (SOF2 and friends: caller re-encodes), `CMYK` (4 components),
  `GRAYSCALE_NOT_ALLOWED` (1 component and `allowGrayscale` is false).
`pad(bytes, targetBytes)`: while `gap = target − size > 0`, insert COM segments (`FF FE`, 2-byte length, payload of `0x20`)
immediately after the JFIF APP0. A segment of total length `n` has `n − 4` payload bytes, `4 ≤ n ≤ 65 537`.
Choose `n = min(gap, 65 537)`; if `gap − n` would be 1, 2 or 3, reduce `n` by `4 − (gap − n)`. If the initial `gap` is
1–3, add one 4-byte segment (overshoot ≤ 3). So the result is exactly `target` bytes unless the gap started below 4.

### 9.4 Fit (`fit(raster, docSpec, options) → FitResult | FitError`)
Window: `min = size_kb.min ?? 0`, `max = size_kb.max` (null max → error `UNKNOWN_LIMIT`), `T = size_kb.target ?? (min+max)/2`.
`margin = max(1, 0.05·(max−min))`, `goal = [min+margin, max−margin]` (KB); if that band is empty (`window < 2 KB`) the goal is the whole window
`[min, max]` (a zero-width goal could never be hit). The effective target is `T' = clamp(T, goal.lo, goal.hi)`; every comparison below against `T` means `T'`. Sizes are the final bytes **after** `patch` (§9.3).
Formats must include `jpg`/`jpeg`, else `UNSUPPORTED_FORMAT`.

**Start size** (the raster is already cropped to aspect, or padded to aspect for ink documents):
- `exact`, `preferred`: `width × height` of the spec.
- `range`: `a` = midpoint of `aspect_w_over_h` (absent: `((minW+maxW)/2)/((minH+maxH)/2)`); `W0 = round((minW+maxW)/2)`,
  `H0 = round(W0 / a)`; if `H0` is outside `[minH, maxH]` clamp it and set `W0 = round(H0·a)`; then clamp `W0` into `[minW, maxW]`.
- `none`: long side `min(L, source long side)` where `L` = 1200 (photo, postcard_photo), 1000 (signature, triple_signature,
  left_thumb, thumb_impression, fingers), 1600 (everything else); the short side keeps the aspect.
Dimensions at scale `k` are `round(w0·k)`, `round(h0·k)` measured from the **start** size, never compounded.

**Search at fixed dimensions** (≤ 8 encodes): evaluate `s95 = size(95)`.
1. `s95 < goal.lo` → evaluate `size(100)`; if it is within `goal` → done with q = 100; otherwise the result is `TOO_SMALL`.
2. Otherwise evaluate `s35 = size(35)`; if `s35 > goal.hi` → `TOO_BIG`.
3. Otherwise bisect integer q in [35, 95] for the largest q with `size(q) ≤ T`, then take whichever of that q and q+1 has a size in
   `goal` and is closer to `T` (ties → the smaller q). If `s95 ≤ T` choose q = 95 (when `s95 ≤ goal.hi`). Result q must satisfy `size ∈ goal`.

**Outer loop.**
- `TOO_BIG`: `exact`/`preferred` → error `TOO_DETAILED`. `range`/`none` → try scales `k = 0.85, 0.85², …` until the size fits or a floor is
  crossed → error `TOO_DETAILED`. Floors: `range`: `w < minW` or `h < minH`; `none`: long side below 600 (photo, postcard_photo,
  documents) or 400 (signature, triple_signature, thumbs, fingers).
- `TOO_SMALL`: `exact`, or `min_fill_strategy = pad_only` → pad. `preferred`/`range`/`none` → try scales `k = 1.25, 1.25², …` (each a
  fresh resample of the source, then the search above) until it fits or the cap is crossed → pad. Caps: `preferred`: `2×` the preferred
  size; `range`: `maxW × maxH`; `none`: long side 2000. Never upscale beyond the cap.
- **Pad**: encode at q = 100 at the final dimensions, `patch`, then `pad(bytes, round(T·1024))`; the result must lie in `goal`
  (clamp the pad target into `[goal.lo, goal.hi]·1024`).
`FitResult.strategy`: `quality_search` (no scaling, no pad), `downscale`, `upscale`, `pad` (pad without upscaling), `upscale_then_pad`.
Aspect is preserved exactly by every scale; `exact` never scales.

### 9.5 Ink cleanup (§3) numbers
Input raster: long side already ≤ 1600 (§1.1). Variants: `signature`, `document` (declaration, triple signature), `thumb`.
- Blur: σ = shortSide/30 approximated by 3 box-blur passes ("boxes for Gauss": ideal width `wI = √(12σ²/3 + 1)`, `wl` = largest odd integer
  ≤ wI, `wu = wl+2`, `m = round((12σ² − 3wl² − 12wl − 9) / (−4wl − 4))` clamped to 0..3, `m` passes of width `wl` then `3−m` of width `wu`; each pass is a
  horizontal then a vertical box blur of radius `(w−1)/2`, integer-rounded `(sum + w/2) / w`), edge pixels replicated.
- `N = min(255, (L·255 + bg/2) / max(bg, 1))`.
- Sauvola (signature, document): window = odd number ≥ `shortSide/20` and ≥ 15, k = 0.34, R = 128, `T = mean·(1 + k·(sd/R − 1))`, ink if `N < T`,
  window clipped at the borders (count the pixels actually inside). Then 3×3 binary opening (erosion treats outside-the-image as ink and dilation as background, so strokes touching the border do
  not erode), then drop 8-connected components smaller than `max(4, 0.0002 · area)` pixels.
- Render: background white; ink is black when `crispBlack`, else the original RGB × 0.6. Default `crispBlack`: signature yes, document no.
- Thumb: no mask. Stretch `N` between its 2nd and 98th percentile (if they coincide, skip the stretch), then gamma 0.8; output grey as RGB.
  "Ink" (for centring and the gate) = stretched pixels below 128.
- Trim: ink bounding box padded by 8 % of its longer side on every side (white beyond the image). Thumb: a **square** of side
  `1.16 × max(bboxW, bboxH)` centred on the centroid of the ink pixels.
- Aspect (§1.2): pad with white, centred, to the slot aspect (`width/height` for exact/preferred, midpoint for range, unchanged for none).
- Quality gate on the trimmed image: coverage `< 0.5 %` → `TOO_FAINT`, `> 35 %` → `TOO_DARK`, else `OK`.
- The three conformance pipelines: `signature_cleanup` (signature), `thumb_cleanup` (thumb), `document_cleanup` (document), always followed by the aspect pad and `fit`.

### 9.6 Photo pipeline numbers
- **Default crop** when a case gives none: the largest centred rectangle with the slot aspect (`exact/preferred`: `width/height`, `range`: midpoint
  aspect); `none` mode: no crop.
- **Auto-framing** from a face box `(x, y, w, h)`: `chinToHairline = 1.25·h`; target coverage 0.675 (midpoint of 60–75 %; a preset
  `face_coverage` overrides); crop height `H = chinToHairline / coverage`, width `H · aspect`; horizontally centred on the face centre; the
  crown `y − 0.125·h` sits at 10 % of `H` from the top. The rectangle is shifted inside the image; if it still does not fit it is shrunk with
  the aspect kept and the result is flagged `coverage_adjusted`.
- **Name/date strip**: strip height `round(0.18 · H)` white at the bottom; the photo area is scaled to the remaining `H − stripH`, **not** stretched
  (letterboxed horizontally if needed). Name: uppercase, bold, size `0.38·stripH`, shrunk down to 60 % of that before truncating with `…`;
  date `DD/MM/YYYY`, size `0.32·stripH`; both centred, black.
- **Face count** (ALGORITHMS 2.1): 0 faces → block ("no face found"); 2 or more → ask the user to re-crop; exactly 1 → continue. A detector returns boxes in
  raster pixels; the largest box is never silently chosen.
- **Strip text layout**: horizontal margin `0.04·W` each side; the two lines form a block `nameSize + 0.08·stripH + dateSize` centred vertically in the
  strip; a line's baseline is its top plus `0.8 × size`; both lines are centred horizontally. The name is truncated with `…` only after it
  has been shrunk to 60 % of its size and still does not fit.
- **Whitening**: feather the person mask with a 3×3 box blur applied twice, then `out = img·α + white·(1−α)`; pixels with α = 1 are unchanged.

### 9.7 Match engine
`FileFacts`: `format`, `kb`, `width?`, `height?`, `color` (`rgb|gray|cmyk`), `progressive`, `docKind`. Only exams with `status = active` take part.
Per (exam, slot of a matching type, §4 mapping) evaluate four constraints:
1. **format**: file format ∈ slot formats (jpg≡jpeg).
2. **size**: `kb ≥ (min ?? 0)` and, if `max` is not null, `kb ≤ max`. A null `max` can never give EXACT.
3. **encoding** (JPEG only): not progressive, and colour `rgb`, or `gray` when the kind is signature/thumb/fingers/declaration **and** `allowGrayscale`.
4. **dims** (images only; a PDF always passes): `exact`: both equal; `preferred`: `|aspect/preferredAspect − 1| ≤ 0.03`; `range`: inside the
   box **and** the aspect range if present; `none`: always.
Verdicts: **UNKNOWN** if `min` and `max` are both null and the mode is `none` (never listed as accepting). **EXACT**: all pass, `max` not null, and
(`preferred`: `|w−W| ≤ 2` and `|h−H| ≤ 2`; other modes: always). **ACCEPTED**: all pass otherwise. **NEAR_MISS**: exactly one constraint fails and:
size is outside the window by ≤ 50 % of its width (`max − (min ?? 0)`) → `compress_to_target` (too big) / `enlarge_to_target` (too small);
format is png/webp/heic and the slot accepts jpg → `convert_to_jpeg`; encoding fails (progressive, CMYK, grey not allowed) → `reencode_baseline`.
A dims failure is never a near miss (it needs a new crop). **NO** otherwise.
Low-confidence exams: EXACT is downgraded to ACCEPTED and the entry is `unverified`; unverified entries never count in the headline numbers
(`acceptedExamCount` counts exams with ≥ 1 non-unverified EXACT/ACCEPTED entry; `quickFixCount` counts non-unverified NEAR_MISS entries).
Result entries list only EXACT, ACCEPTED, NEAR_MISS, sorted by verdict (EXACT, ACCEPTED, NEAR_MISS), then position in the popularity list
(unlisted after listed), then exam name (compared by Unicode code point). An exam with several matching slots appears once per slot.

### 9.8 Export naming
`examShort` = the exam name cut at the first ` / ` or ` (`, every run of non-alphanumeric characters replaced by one `-`, trimmed of `-`
(`IBPS PO / MT` → `IBPS-PO`). File name: `filename ?? docType`-based: if the slot has `filename`, exactly `<filename>.jpg`; otherwise
`<docType>_<examShort>_<W>x<H>_<K>kb.jpg` with `K = round(bytes / 1024)`.

### 9.9 Conformance case keys (`fixtures/cases.json`)
A fit case names either `preset` + `doc`, or an inline `spec` (a full document object, used for modes no real preset has, e.g. `exact`).
`crop {x,y,w,h}` is in *original source* pixels (a decoder that downsamples scales it by `decodedWidth/sourceWidth` and
`decodedHeight/sourceHeight`, `round`ed, then clamped into the raster); `pipeline` is absent (photo kinds: crop+fit; other kinds: pad-to-aspect+fit),
`signature_cleanup`, `thumb_cleanup` or `document_cleanup`. `expect` keys, checked on the **re-inspected written bytes**:
`format: "jpeg_baseline"` (JPEG, SOF0), `color` (`rgb`), `kb_min`/`kb_max` (inclusive, KB = 1024), `width`/`height` (equal, unless
`allow_upscaled_preferred`: then the aspect must match within 1 px of rounding and the size lie in [1×, 2×]), `w_range`/`h_range`/`aspect_range`
(inclusive), `aspect` ± `aspect_tol`, `background_mean_min` (median luma of the whole image ≥ value), `ink_pixels_min_pct` (% of pixels with luma < 128 ≥ value),
`exif: "none"` (no APP1), `dpi` (JFIF units 1 and both densities equal), `export_filename` (§9.8), `decodes` (re-decodes without error),
`max_ms_midrange` (measured by the benchmark, not asserted in unit tests). Other sections: `inspect_cases`, `decode_cases`, `patch_cases`,
`geometry_cases`, `match_cases`, `search_cases` (their keys are documented inline in the file).

## 10. Exam search and browse (Home, PRD F1)
A pure function on both platforms (`ExamSearch`) over the presets bundle. Conformance: `search_cases`, whose expected results are
computed by the reference implementation `spec/tools/exam_search.py` (a third, independent implementation).

**Data** (all in the signed bundle, so search improves with a presets update, never with an app release):
`exam.aliases` (optional: other spellings and Hindi forms, e.g. `आईबीपीएस पीओ`, `bank po`), `bundle.categories[<category>].aliases`
(e.g. banking: `bank`, `बैंक`) and `bundle.popular` (exam ids, most popular first; Remote Config `popular_exam_order` may override it
from Phase 4). Older bundles without these fields behave as if they were empty.

**Visible exams.** `status = active`, and `confidence = low` only when the "Show unverified exams" setting is on.

**Normalisation `norm(s)` → tokens.** (1) Unicode NFKC. (2) Lower-case every scalar with the Unicode default mapping (no locale rules).
(3) Delete U+093C (Devanagari nukta), U+200C and U+200D. (4) Every scalar that is not a letter (`L*`), mark (`M*`) or decimal digit (`Nd`)
becomes a space; Devanagari vowel signs and virama are marks, so Hindi words stay whole. (5) Split on spaces, dropping empty tokens.
Lengths and comparisons below are in Unicode scalars; `_` in ids is punctuation, so `state_psc` → `state psc`.

**Index of an exam:** the tokens of `name`, `body`, `id`, `category`, each category alias and each exam alias, plus one *joined*
token per phrase of `name` and of each alias (its tokens concatenated: `IBPS PO / MT` → `ibpspomt`), so `ibpspo` and `sscchsl` match.

**Token level `level(q, t)`:** 3 if `q = t`; 2 if `t` starts with `q`; 1 if `|q| ≥ 4` and `osa(q, t[0..k]) ≤ 1` for some
`k ∈ {|q| − 1, |q|, |q| + 1}` with `k ≤ |t|` (one typo: insertion, deletion, substitution or adjacent swap, against a prefix of
`t`); else 0. `osa` is the optimal string alignment distance (Levenshtein plus adjacent transposition, each cost 1).

**Search(query, category?).** `Q = norm(query)`. Empty `Q` → no results (the screen shows its browse sections instead). Candidates are the
visible exams, restricted to `category` when a chip is selected. An exam matches when every `q ∈ Q` has `max_t level(q, t) ≥ 1`;
its score is `Σ_q max_t level(q, t)`. Order: score descending; then exams whose normalised name (tokens joined by one space) starts
with the normalised query (tokens joined by one space) first; then popularity rank (position in `popular`, unlisted after all listed);
then normalised name by scalar order; then id.

**Browse(category).** The visible exams of the category, ordered by popularity rank, then normalised name, then id.
**Popular(n).** The first `n` visible exams in `popular` order; unknown or invisible ids are skipped.

