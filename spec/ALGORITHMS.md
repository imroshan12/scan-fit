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

| Mode        | Crop / fit                                                                       | Output size                                                                                    |
| ----------- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `exact`     | Crop to exactly the target aspect ratio (user can adjust the crop; locked ratio) | exactly `width×height`                                                                         |
| `preferred` | Crop to target aspect                                                            | `width×height` initially; may upscale in §1.4 keeping aspect                                   |
| `range`     | Crop to an aspect within `aspect_w_over_h` (default to the midpoint)             | the largest size within `[min,max]` box that the source supports, starting at the midpoint box |
| `none`      | User crop, free ratio                                                            | long side 1200px (photo), 1000px (signature/thumb), 1600px (documents)                         |

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

Photo review saving is a separate operation: idle → saving → saved or retryable error. Only a ready,
matching review can be saved. While saving, reject duplicate saves and changes to the review. A cancelled
destination picker returns to idle without an error or a checklist update. A write/read failure is a
save error; a failed verification is a verification error. Delete any newly created partial or invalid
destination on failure (best effort; never claim it was removed if deletion failed).
Verification also requires SOF0, no EXIF/GPS/XMP, JFIF units 1 and both densities equal to `spec.dpi ?? 200`,
and written bytes identical to the review bytes. The slot verdict must be EXACT or ACCEPTED (§9.7).
Android keeps a MediaStore entry pending until verification succeeds; a publish failure is a save error.
iOS uses the Files picker and re-opens its returned URL with security-scoped access and coordinated reading.
The iOS picker chooses the folder; `ScanFit/<Exam>/` is recommended, not forced. Existing user files are
never deleted on picker cancellation. Save failures keep the review available for retry.
Only after successful verification and publication, persist the exam/document's Saved checklist status
locally. A later failed or cancelled save does not clear a previously verified Saved status. This status
records a successful export, not a guarantee that the user has kept the file. Sharing and Save all are §1.6.2; export
history is §13.3; private retained drafts are defined in §1.6.1. Conformance: `export_cases`.

### 1.6.1 Retained drafts and Ready checklist rows

Photo and ink flows retain one **final fitted JPEG** per `(exam_id, doc_type)` locally, automatically after a successful
render. This is not a cleaned original, export history or a user-visible export. Persist only output passing the full
§1.6 verifier as the slot's `DocKind`; re-open the private written file and verify byte equality before publishing Ready.
Retaining a draft never sets Saved. A storage failure leaves the in-memory review exportable, shows
`draft.retain_failed`, and never claims that a draft was retained. A previous valid draft survives a failed replacement.

Storage is app-private and excluded from device/cloud backup (Android no-backup storage; iOS Application Support with
`isExcludedFromBackup`). No images, paths, names or draft metadata are logged or transmitted. One versioned structured
record contains JPEG bytes and its creation time, committed atomically, so process death cannot publish half a record.
Use an opaque file name derived from the trusted slot key, never concatenate a caller's exam id into a path. Reject
symlinks, unsupported versions and oversized records; maximum JPEG payload is 64 MiB (encoded record at most 96 MiB).
The record may use the platform's structured serializer with base64 binary data; no new DB or external dependency is
required for this single-record-per-slot store. Metadata derived from JPEG bytes is never trusted instead of inspection.

On every read, verify using the **current trusted slot**, including baseline RGB, stripped privacy metadata, size,
dimensions and DPI. Only a record aged `0 <= age < 30 days` is available (`30 days = 2,592,000 seconds`); missing, corrupt,
expired, future-dated or nonconforming records produce no Ready status, with best-effort removal of invalid records.
Revalidate on checklist/flow entry and app foreground, and after store changes. A preset update must not leave a cached
Ready claim. Storage and inspection run off the UI thread. Late loads/renders must not overwrite newer user actions or
retain their obsolete bytes; cancellation/generation checks guard persistence and UI publication.

Checklist precedence preserves existing history: **Saved** if a verified export was previously recorded; otherwise
**Ready · {rounded KB}** only for an available verified draft; otherwise **Not started**. Saved is historical and does not
assert that a retained or exported file still exists. Ready technical checks for an unverified preset do not remove its
badge or imply official acceptance. Signature drafts may be retained before the one-time handwriting confirmation;
restored review still requires that confirmation before Save.

Entering a photo/ink slot first attempts to load its draft. A valid draft opens a read-only review rebuilt from the
retained bytes (inspection, slot verdict, match note and check chips), with idle export state and the normal Save action.
No face detection, cleanup, fitting, whitening or name/date renderer runs. No Before image or editable processing
controls are invented: the original crop/options are not retained. `draft.replace` returns to source selection and keeps
the previous draft until a new verified output replaces it. Back from a restored review returns to source selection;
back from a newly rendered review still returns to its crop. Picker cancellation and failed saves leave the draft intact.
Shared conformance cases: `draft_cases`; additional native tests cover atomic replacement failure, stale jobs, current
preset validation, app relaunch, exact retained/exported bytes and checklist updates.

**My Kit prepared files.** My Kit lists available photo/ink drafts from active exams in the current trusted bundle,
including unverified exams regardless of the Home visibility setting (keep their confidence badge). Group by exam in
bundle order and list slots in preset document order. Each row carries its document type and rounded KB and opens that
slot's restored review. The app composes navigation; Kit never imports the flow features. Loading is explicit; failure
to load trusted presets is a retryable error, not an empty kit. Empty means verification found no available drafts.
Revalidate on tab entry/foreground/store changes; cancel obsolete loads. Enumerate trusted slots through existing
draft reads, not untrusted filesystem metadata. Kit does not retain JPEGs in its UI model or decode full inputs.
These are prepared exam files, not reusable cleaned originals or export history. Existing missing/expired files and
files produced before draft retention was implemented cannot be recovered from a historical Saved marker. Originals,
cross-exam reuse and history are §13 (Prepared is the first of Kit's three views). Viewing Kit never records Saved or extends retention.

### 1.6.2 Share and Save all

**Share** (review screen, secondary action next to Save). Enabled exactly when Save is (a ready render that meets the
slot, no save running, and the one-time handwriting confirmation for exam signatures). Share hands the system share sheet
the **review bytes unchanged**, named per §1.7/§9.8: write them to an app-private share folder (Android cache `share/`,
exposed read-only through the app's FileProvider for that one intent; iOS temporary `share/`), re-read the file and require
byte equality before presenting the sheet, else show `export.share_failed`. One staged file at a time: the folder is
emptied before each share and on app start. Sharing is user-initiated, so CLAUDE.md rule 2 holds (the app sends nothing
itself), but the destination cannot be re-read, so Share never records Saved and never changes the save state.

**Save all** (exam checklist, bottom action `exam.save_all` with the count). The plan is computed from the checklist in
preset document order (`SaveAllPlan`, conformance `save_all_cases`): a slot is **included** when its row is Ready (§1.6.1:
an available verified draft and no Saved record); an exam signature or triple signature whose handwriting confirmation
is still missing is **held** instead (its row stays Ready; `export.save_all_held` explains; the review asks for the tick). The action shows only when at
least one slot is included and the platform saves without a per-file picker: Android API 29+ (MediaStore, no prompt) and
iOS (one Files export picker for all files, `UIDocumentPickerViewController(forExporting:)` with every staged URL; the
user picks one folder). Android 8–9 needs a picker per file, so there the rows are saved one by one (deviation).

Each included slot re-reads its draft (it may have expired since the checklist loaded; a missing draft is a failure for
that slot), then runs the full §1.6 write → re-read → verify → publish as the slot's `DocKind`, sequentially, and records
Saved per verified file. A failure never stops the others and never removes another file. iOS matches each returned URL to
its request by **byte equality** (the picker may rename on "Keep both"), so a URL that matches no request is never
verified as, or deleted for, the wrong slot. Cancelling the iOS picker saves nothing and records nothing.

Outcome: `saved` count and the `failed` slots. All saved: `flow.saved_to` with the folder (Android
`Downloads/ScanFit/<examShort>`, iOS the picked folder's localized name, or `export.files_app` at a provider's root) and, on Android, `flow.open_folder` (opens the system
Downloads view; a specific subfolder cannot be targeted portably). Some failed: `export.save_all_partial` and a retry that
re-plans from the current checklist, so already-saved slots are not written twice. Nothing is held in memory beyond the
JPEGs of the running save.

### 1.7 Export naming

`<filename or docType>_<examShort>_<WxH>_<KB>kb.jpg`, e.g. `signature_IBPS-PO_140x60_16kb.jpg`.
If the preset has `filename` (NTA: `Photograph`, `Signature`), use exactly `<filename>.jpg`, and put
files in a per-exam folder so names don't collide:
Android `Downloads/ScanFit/<Exam>/`, iOS `Files › ScanFit › <Exam>/`.

---

## 2. Photo pipeline

1. **Face detection** (Android ML Kit Face Detection, bundled model; iOS Vision
   `VNDetectFaceRectanglesRequest`: only the box is used). 0 faces → block with a message. >1 face → ask to re-crop.
2. **Auto-framing**: the face box height (chin to hairline estimate = face box × 1.25) should be
   60–75% of the output height, horizontally centred, with the top of the head 8–12% from the top.
   Presets may override this later with a `face_coverage` field (UPSC: about 75%; SSC live: about 80%).
   The user can adjust (move, zoom, rotate 90°); the crop stays aspect-locked and inside the image (§9.6).
3. **Background whitening** (optional toggle, off by default): person segmentation (Android ML Kit
   Selfie Segmentation; iOS `VNGeneratePersonSegmentationRequest`, quality `.accurate`). Feather the
   mask 2–3px, composite onto `#FFFFFF`. Never alter pixels inside the mask. Show before/after.
4. **Name/date strip** (off by default; the user turns it on. When the preset has `name_date_strip.required`, the
   toggle shows a hint to check the notice, since guides often disagree on it): a white
   strip at the bottom equal to **18%** of the output height, added _inside_ the target dimensions
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
   k = 0.34, R = 128. **Signatures (including triple): preserve the thresholded mask without erosion or component
   removal**, since fine strokes, dots and disconnected flourishes are meaningful ink. Documents only: morphological
   open (3×3) and small-component filtering as §9.5. Crop unwanted marks manually rather than deleting signature parts.
   **Thumb**: no binarisation (ridges matter), and **no local-mean normalisation**: a thumb print is a large dark
   object, so step 3 alone turns every heavily inked area wider than its blur white and loses the print's shape.
   Instead estimate the paper from paper pixels only (the print is masked out, a wide blur fills it in from the
   surrounding paper), normalise by that, then stretch linearly from the darkest ink to near-white paper and darken
   the mid-greys slightly so faint ridges stay visible (§9.5). Never lighten ink.
5. **Render**: background = pure white. Ink colour = the original pixel darkened ×0.6 (keeps blue or
   black), or pure black when "Crisp black" is on (default for signatures).
6. **Trim**: bounding box of the ink + padding of 8% of the box's long side. Thumb: a square crop centred on the
   (robust) bounding box of the ink, so the whole print is always inside — never on the ink centroid, which
   clips the side away from the heavier ink.
7. **Fit to aspect** by padding with white (§1.2), then §1.
8. **Quality gate**: ink coverage must be 0.5–35% of the area; otherwise warn "too faint" or
   "too dark / not on white paper".

Do **not** try to detect CAPITAL-letter signatures. It isn't reliable from images. Show a
one-time checkbox instead: "I signed in running handwriting, not CAPITAL letters".

**Flow (both apps).** Pick (camera, gallery or Files) → free rectangular crop (starts as the whole image; the user drags
corners to leave out other marks) → cleanup (steps 2–6) → pad to aspect + fit (step 7) → review → save (§1.6, with the
slot's match kind). Rectification (step 1) comes with the document scanner; until then the crop is an axis-aligned
rectangle. Review offers "Crisp black" (signature/document) and "Darker ink" (when crisp black is off); a thumb has
neither. The quality gate warns but does not block saving. A declaration shows the preset's `declaration_text` to copy
by hand before capture, or "Copy the text from the official notice" when it is null. Exact numbers: §9.5.

## 4. Match engine — "which exams accept this file?" (feature: exam note on every edit)

Input: an `InspectedFile` (format, bytes, width, height, colour, progressive, dpi) plus `docKind`
(`photo | signature | thumb | declaration | fingers | pdf_document`). The kind comes from the flow
that produced the file, or a user pick in the Checker.

Kind → preset document types:
`photo → photo, postcard_photo` · `signature → signature, triple_signature` ·
`thumb → left_thumb, thumb_impression` · `declaration → handwritten_declaration` ·
`fingers → left/right_hand_fingers_thumb` · `pdf_document → any PDF-format type`.

For every active exam and matching slot, evaluate the hard constraints:

| Constraint        | Pass rule                                                                        |
| ----------------- | -------------------------------------------------------------------------------- |
| format            | file format ∈ `formats` (jpg ≡ jpeg)                                             |
| size              | `min ≤ KB ≤ max` (a null max is treated as unknown → at best ACCEPTED)           |
| colour / encoding | RGB (or allowed gray), baseline                                                  |
| dims `exact`      | equal to ±0 px                                                                   |
| dims `preferred`  | aspect within ±3% of preferred (size may differ)                                 |
| dims `range`      | inside the box and the aspect range                                              |
| live-photo exams  | exams whose photo is captured live have no photo slot → never listed for `photo` |

Result per exam:

- **EXACT**: all pass, and preferred dims match within ±2 px (or the mode is exact/range).
- **ACCEPTED**: all pass, but preferred dims differ, or max is null.
- **NEAR_MISS**: exactly one constraint fails _and_ it's fixable in one tap: size outside the window
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

**Match note on review.** Exam flows (photo, ink) show the note under the slot verdict on every render: "Accepted by N exams"
with the first three names, a "likely OK" count for unverified exams, and a detail sheet grouped by body (§9.7 _Match note_).
The file in an exam flow is made for that exam's slot, so a near miss there says what the other exam needs ("SSC CGL ·
Signature — Needs ≤ 20 KB") **without** a Fix button; Fix belongs to the Checker and Custom resize, where a file has no slot yet.

**Review comparison and chips (photo and ink).** Before shows the selected, oriented crop before whitening, strip,
cleanup, padding or fitting; After shows the decoded final JPEG. Reuse the bounded crop already in memory, with image
conversion/decoding off the UI thread. After is selected on entry. The native segmented selector and a press-and-hold
on After temporarily show Before; release/cancellation restores the selected segment. Both images use one stable,
letterboxed preview frame. Comparison is display-only: it never renders, clears Saved, changes options or changes the
bytes saved. Hide comparison until a ready result exists; hide stale output and chips while fitting or after failure.

Three wrapping chips show the output's rounded KB, inspected dimensions and JPG encoding, with an icon and a spoken
pass/fail status. Use the slot's `SlotEvaluation` on those exact output bytes: size fails for `size_kb`, dimensions for
`dims`, and JPG for either `format` or `encoding`. `UNKNOWN` makes all three unchecked (never green). These are
technical checks, not a promise of portal acceptance: keep the slot verdict underneath and retain "Likely OK ·
Unverified" for low-confidence presets. The shared `review_check_cases` pin the mapping; DPI and privacy metadata
remain mandatory export verification checks (§1.6), not implied by a JPG chip. No new image is decoded at full input size.

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

## 6. PDF engine (PRD F13)

One engine makes every PDF the app writes: **images → PDF**, **compress a PDF to a KB limit**, **merge PDFs**, **ID card on
one page**, and the **PDF maker** of an exam's PDF slot (e.g. JEE Main "Class 10 certificate", 50–300 KB). The output is
always an *image PDF* the app writes itself (§6.4): each page is one or two baseline JPEGs drawn on a page. Text in the
input becomes part of an image (not selectable). That is fine for portal uploads, and it is what lets both apps hit a KB
window without a PDF library (decision 2026-10-15; TECH_FEASIBILITY §2). Nothing about the input or output is logged.

### 6.1 Jobs, sources and options

A **job** is an ordered list of sources, a size window and options. Sources: an **image** (JPEG, PNG, WebP, HEIC; read with
the usual 64 MiB bound, decoded by §1.1 with a long-side cap of 2400 px, or 1600 px when cleanup is on (§9.5 input size),
EXIF-oriented), with an optional free crop (§9.5 free crop, on the decoded raster) and quarter turns; or a **PDF** (each of
its pages, in order). What the tool lets the user add:

| Tool           | Sources                                   | Pages                                          |
| -------------- | ----------------------------------------- | ---------------------------------------------- |
| `images`       | 1+ images                                 | one image page per image                       |
| `compress`     | exactly 1 PDF                             | one PDF page per input page                    |
| `merge`        | 2+ PDFs                                   | every page of every PDF, in order              |
| `id_card`      | exactly 2 images: front, back             | one ID-card page                               |
| `exam` (slot)  | 1+ images or PDFs, mixed, in any order    | image pages and PDF pages in source order      |

At most **20 output pages** (`TOO_MANY_PAGES`). A PDF whose Inspector `encrypted` flag is set (§9.2) is refused before
rendering (`ENCRYPTED`, "This PDF has a password. Remove the password first."). A PDF the platform renderer cannot open,
or an image that does not decode, is `UNREADABLE` and names nothing about the file.

**Window.** An exam slot gives `size_kb` (§9.4 window, goal and `T'`); a slot without `max` is `UNKNOWN_LIMIT`. Tools ask
for **Max KB** (required, whole KB `1–5120`, quick picks `100 · 200 · 300 · 500`, default `200`), with no minimum:
`{min: null, max, target: null}`.

**Options.** `grayscale` (default **on**) and `cleanup` (default **on**; applies to image sources only; shown only when the
job has an image). Both are the user's decision 2026-10-15: certificates look like a clean grey scan by default and come
out about half the size; seals and stamps lose their colour unless the user turns grayscale off.

**Unchanged.** A job whose only source is one PDF that is not encrypted and whose size already lies in the window
(`min ≤ KB ≤ max`) returns that file's bytes unchanged (strategy `unchanged`), and the result says
"This PDF already fits. Nothing was changed." No option applies to it.

### 6.2 Preparing image pages

Per image source, once per job: decode (§6.1) → quarter turns → crop → `cleanup` (§9.10 *Page cleanup*) if on, else
`grayscale` alone turns every pixel into its luma `L` (§9.1) as R = G = B = L. The prepared raster is reused for every DPI
of §6.3. PDF pages are rendered at each DPI on a white background; `grayscale` turns them into luma the same way (cleanup
never applies to them: a PDF page is already a clean page).

### 6.3 Fitting to the window (compress to ≤ X KB)

For the DPI ladder `[200, 150, 120, 100]`, highest first: render every page at that DPI (§9.10 layout), encode every page
image as a baseline JPEG at each quality of the grid `[85, 75, 65, 55, 45, 40]`, and measure the written PDF (§6.4) for
each quality; at the last rung (100 DPI) the grid continues with `[35, 30, 25, 20]` (iOS ImageIO's quality scale
compresses less than Android's libjpeg, so a dense page can need it there). The **first DPI** whose lowest-quality PDF
is `≤ goal.hi` is used, at the **highest quality** of its grid whose PDF is `≤ goal.hi`. If even 100 DPI at quality 20
is too big → `CANNOT_REACH`: "Can't get this under X KB. Try fewer
pages or crop the margins." When the chosen PDF is below `goal.lo` (a small file for a slot with a minimum, e.g. JEE's
50 KB), it is **padded** (§6.4) to `round(T'·1024)` bytes — the pages are unchanged; see §1.4 for why minimums are
padded. Exact numbers and the selection rule: §9.10 (conformance `pdf_fit_cases`).
A fixed grid instead of a binary search means each page is rendered once per DPI and only one page raster is in memory at a
time; the bytes of the chosen DPI and quality are produced by a final pass (render + encode, deterministic).
Strategy (analytics enum): `quality` (200 DPI, no pad), `lower_dpi`, `pad`, `unchanged`.

### 6.4 The PDF the app writes

PDF 1.4, no metadata (no `/Info`, title, author, producer or dates), no fonts, no compression of the object structure, one
`/DeviceRGB` `/DCTDecode` image XObject per placed image, each JPEG passed through the §1.5 patcher (DPI = the render DPI)
so it carries no EXIF, XMP or ICC. Grey pages are encoded as RGB JPEGs whose three channels are equal (identical on both
platforms; the chroma costs almost nothing). Exact bytes: §9.10 *Writer* (conformance `pdf_writer_cases`, byte-identical
on both platforms and in the reference). **Padding** appends one unreferenced stream object of spaces before the
cross-reference table, so every reader ignores it.

### 6.5 Verify after write

§1.6 applies with these checks on the re-read bytes: identical to the result bytes; Inspector format `pdf`, not
encrypted, and (for a PDF the app wrote) `pages` equal to the job's page count and `imageOnly`; size in the window; for an
exam slot the slot verdict (§9.7, kind `pdf_document`) is EXACT or ACCEPTED. A Tools PDF is checked against its synthetic
slot (§6.6). Saving records Saved for an exam slot and appends a history entry (§13.3). Android saves to
`Downloads/ScanFit/<Exam>/` (MIME `application/pdf`; Tools: `Downloads/ScanFit/PDF/`), iOS through the Files picker.

### 6.6 Screens, names and the Checker

**Tools → PDF** lists the four tools; an exam's PDF row opens the same **PDF maker** for that slot (decision 2026-10-15).
Tools use a synthetic slot: exam id `pdf`, exam name `PDF` (not translated: it names the file and the folder),
`type` `id_card` for the ID card and `document` for the other tools (two schema document types added for this; no preset
uses them yet), `formats = [pdf]`, `dimensions.mode = none`, the window above. Names (§9.8): an exam
slot with `filename` → `<filename>.pdf`; otherwise `<docType>_<examShort>_<K>kb.pdf` (`class10_certificate_JEE-Main_182kb.pdf`,
`id_card_PDF_96kb.pdf`). The **Checker** offers "Make it fit" for a PDF slot when the file is a decodable image or a PDF
that is not encrypted (§12): the file becomes a one-source `exam` job for that slot with the default options.

**Images → PDF** places each image on its own A4 page, 5 % margins, portrait or landscape by the image (§9.10).
**ID card**: front above back on one A4 portrait page, each at true scale 85.6 × 54 mm, 15 mm apart, centred (§9.10);
the user crops each side to the card. **Merge** and **compress** keep each page's own size (pages larger than A4 are
rendered at A4's pixel budget).
Paid limits (PRD: compress of more than 3 pages is Pro) come with the paywall in Phase 4; nothing is limited yet.

## 7. Live photo coach (practice only, nothing is uploaded)

Real-time on camera frames at ≥10 fps analysed, preview at 30 fps:

| Check         | Rule (pass)                                                                                    | Source                                                        |
| ------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| One face      | exactly 1                                                                                      | face detector                                                 |
| Size          | face box height 45–60% of frame height (≈ 80% of the portal oval)                              | box                                                           |
| Frontal       | \|yaw\| < 10°, \|roll\| < 8°, \|pitch\| < 12°                                                  | Android Euler angles / iOS `VNFaceObservation.yaw/roll/pitch` |
| Eyes open     | both eye-open probability > 0.6 (Android); eye aspect ratio from landmarks > 0.2 (iOS)         |                                                               |
| Lighting      | face mean luminance 90–200; left/right half difference < 25                                    | pixels                                                        |
| Background    | border region (outer 12%) luminance std-dev < 22 and mean > 150                                | pixels                                                        |
| Sharp         | variance of the Laplacian on the face crop > threshold calibrated per device class (start: 60) | pixels                                                        |
| iOS extra     | `VNDetectFaceCaptureQualityRequest` ≥ 0.5                                                      | Vision                                                        |
| Glasses / cap | **not auto-detected** (no reliable on-device API). Show a reminder chip                        | —                                                             |

All checks green for 1 second → haptic, then "Looks good — do the same on the portal". Thresholds
live in Remote Config (`coach_thresholds`) so they can be tuned without a release.

## 8. Conformance

`fixtures/cases.json` is run by both platforms' unit tests (Android runs Robolectric with real Skia
encoding via `robolectric` native graphics mode, or on an instrumented device job; iOS runs it
directly). A PR that changes engine behaviour and breaks a case must update the case **and** explain
why in the PR description.

---

## 9. Precise definitions (the conformance contract)

§1–§8 say _what_ happens. This section pins every number and edge case that two independent implementations
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

Input raster: long side already ≤ 1600 (§1.1). Variants: `signature` (including triple signature), `document` (declaration), `thumb`.

- Blur: σ = shortSide/30 approximated by 3 box-blur passes ("boxes for Gauss": ideal width `wI = √(12σ²/3 + 1)`, `wl` = largest odd integer
  ≤ wI, `wu = wl+2`, `m = round((12σ² − 3wl² − 12wl − 9) / (−4wl − 4))` clamped to 0..3, `m` passes of width `wl` then `3−m` of width `wu`; each pass is a
  horizontal then a vertical box blur of radius `(w−1)/2`, integer-rounded `(sum + w/2) / w`), edge pixels replicated.
- `N = min(255, (L·255 + bg/2) / max(bg, 1))`.
- Sauvola (signature, document): window = odd number ≥ `shortSide/20` and ≥ 15, k = 0.34, R = 128, `T = mean·(1 + k·(sd/R − 1))`, ink if `N < T`,
  window clipped at the borders (count the pixels actually inside). Signature keeps this mask unchanged: do not erode
  strokes or delete components by size. Document only: 3×3 binary opening (erosion treats outside-the-image as ink and
  dilation as background, so strokes touching the border do not erode), then drop 8-connected components smaller than
  `max(4, 0.0002 · area)` pixels. Conformance: `ink_preservation_cases` retain all threshold-detected signature pixels,
  including one-pixel strokes and isolated marks on each of three signatures. The trim must include all retained marks.
- Render: background white; ink is black when `crispBlack`, else the original RGB × 0.6. Default `crispBlack`: signature yes, document no.
- **Thumb** (no binarisation; `percentile(p)` of a histogram of `n` values = the smallest level whose cumulative count
  exceeds `⌊p·n⌋`):
  1. Candidates: `N₁` = the `N` above (σ = shortSide/30). Otsu level `v*` of the histogram of `L` = the `v` maximising
     `w₀·w₁·(μ₀ − μ₁)²` for the split `L ≤ v` / `L > v` (first maximum; `v* = 255` when `L` has a single level).
     A pixel is a **candidate** (ink or its halo) if `N₁ < 230` or `L ≤ v*`; then dilate the candidates twice with a
     3×3 square (outside the image is not a candidate).
  2. Paper: `P` = percentile(0.5) of `L` over non-candidates (255 if every pixel is a candidate). `M` = 255 on
     non-candidates, 0 on candidates; `B = blur(M)`, `C = blur(L on non-candidates, 0 on candidates)`, both with the
     box-blur Gaussian above at σ₂ = shortSide/8; `bg = (C·255 + 5·P) / (B + 5)` (integer division). Far from any
     paper `bg → P`, so a print filling the crop is normalised by the global paper level, not by itself.
  3. `N = min(255, (L·255 + bg/2) / max(bg, 1))` with this `bg`.
  4. Tone: black point `b` = percentile(0.02) of `N` over candidates (0 if there are none), white point 235:
     `s = min(max(N − b, 0), 235 − b) / (235 − b)` (`s = N/255` if `b ≥ 235`), `out = round(255 · s^1.25)`.
     Paper at ≥ 235 becomes pure white; the exponent darkens mid-greys (faint ridges) and never lightens ink. Output grey as RGB.
  5. Ink for the trim = `out < 240` (every visible mark, including a faint rim); ink for the gate = `out < 128`.
- Trim: ink bounding box padded by 8 % of its longer side on every side (white beyond the image). Thumb: with `k` trim-ink
  pixels, `x₀`, `x₁` = the x-coordinates at sorted positions `⌊0.005·(k−1)⌋` and `⌊0.995·(k−1)⌋` (likewise `y₀`, `y₁`), so a
  few stray specks cannot stretch the box. A **square** of side `s = max(1, round(1.16 · max(x₁−x₀+1, y₁−y₀+1)))` with origin
  `(⌊(x₀+x₁+1)/2⌋ − ⌊s/2⌋, ⌊(y₀+y₁+1)/2⌋ − ⌊s/2⌋)`: centred on the box, which it always contains. `k = 0` → the whole image, `TOO_FAINT`.
  Thumb coverage = gate-ink pixels inside the square ÷ `s²` (pixels beyond the image are white).
  Conformance: `thumb_preservation_cases` (parametric prints: a heavy-ink core stays dark, the print never touches the
  output border, shaded paper turns white).
- Aspect (§1.2): pad with white, centred, to the slot aspect (`width/height` for exact/preferred, midpoint for range, unchanged for none).
- Quality gate on the trimmed image: coverage `< 0.5 %` → `TOO_FAINT`, `> 35 %` → `TOO_DARK`, else `OK`.
- The three conformance pipelines: `signature_cleanup` (signature), `thumb_cleanup` (thumb), `document_cleanup` (document), always followed by the aspect pad and `fit`.
- **Doc type → cleanup variant / match kind** (§4): `signature`, `triple_signature` → signature / SIGNATURE; `left_thumb`,
  `thumb_impression` → thumb / THUMB; `left_hand_fingers_thumb`, `right_hand_fingers_thumb` → thumb / FINGERS;
  `handwritten_declaration` → document / DECLARATION. The export check (§1.6) evaluates the slot with this kind.
- **Review options:** `crispBlack` defaults per variant (signature on, document off); "Darker ink" sets `inkFactor` in
  `[0.3, 0.9]` in steps of 0.1 (default 0.6, lower = darker), only while crisp black is off. Thumb: no options.
- **Free crop (ink)** on a `W×H` image: starts as `(0, 0, W, H)`. `move` as §9.6. `resize(corner, dx, dy)` moves the dragged
  corner (`tl`, `tr`, `bl`, `br`) by `round(dx)`, `round(dy)` and keeps the opposite corner fixed; each side stays
  `≥ min(32, W)` / `≥ min(32, H)` and inside the image: e.g. `br`: `right = clamp(round(x + w + dx), x + minW, W)`,
  `bottom = clamp(round(y + h + dy), y + minH, H)`; `tl`: `x' = clamp(round(x + dx), 0, x + w − minW)`, `y'` likewise, the
  right and bottom edges unchanged. Conformance: `crop_cases` op `resize`.
- **Signature confirmation:** before the first save of a signature or triple signature, the user ticks "I signed in running
  handwriting, not CAPITAL letters"; the tick is remembered on the device and never asked again.

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
  The segmenter runs on the **cropped** raster. Its per-pixel person confidence becomes the mask by a hard threshold, `α = 255` if
  confidence ≥ 0.5 else `0` (a model mask of another size is first resampled bilinearly to the raster size), so the feathering above is the
  only softening and confident person pixels are never lightened.
- **Order**: decode (§1.1) → face check → auto-framing → user adjust → crop → whitening (if on) → name/date strip (if on) → fit (§9.4)
  with the cropped raster as the source (no second crop).
- **Manual adjust** (image `W×H`, crop `(x, y, w, h)`, slot aspect `a`): the crop stays aspect-locked and inside the image.
    - `move(dx, dy)`: `x' = clamp(round(x + dx), 0, W − w)`, `y' = clamp(round(y + dy), 0, H − h)`.
    - `zoom(f)` (`f > 1` zooms in, i.e. a smaller crop): `maxH = min(H, floor(W / a))`; `minH = min(maxH, 64 if a ≥ 1 else ceil(64 / a))` (the
      short side never drops below 64 px); `h' = clamp(round(h / f), minH, maxH)`, `w' = min(W, round(h'·a))`; the centre is kept:
      `x' = clamp(round(x + w/2 − w'/2), 0, W − w')`, `y' = clamp(round(y + h/2 − h'/2), 0, H − h')`.
    - `rotate`: the raster turns 90° clockwise (EXIF transform 6); faces are detected again and the crop re-framed, or the default crop
      when there is not exactly one face.
      Conformance: `crop_cases` (`frame`, `move`, `zoom`).

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
   Each entry also carries its slot's `size_kb` window (for the need text below).

**Match note** (`MatchNote.of(result)`; conformance: `match_note_cases`, computed by `spec/tools/match_note.py`). From a sorted result:
`accepted` = `acceptedExamCount`; `preview` = the first 3 of `acceptedExamIds` (result order); `more` = `accepted − |preview|`;
`likely_ok` = distinct exams with an unverified EXACT/ACCEPTED entry that are not in `acceptedExamIds`; `quick_fixes` = the
non-unverified NEAR_MISS entries in result order, each with a `need`: `compress_to_target` → `at_most floor(max)` KB,
`enlarge_to_target` → `at_least ceil(min)` KB, `convert_to_jpeg` → `jpeg`, `reencode_baseline` → `baseline`; `groups` = every entry,
grouped by `body`, groups ordered by their first entry, entries in result order. Headline: `accepted > 0` → `match.accepted` plus
the preview names joined by " · " (and "+`more`" when `more > 0`); else `likely_ok > 0` → `match.likely_ok_count`; else `match.none`.
A quick-fix line (`match.quick_fix`) shows when `quick_fixes` is not empty. Unverified entries in the sheet use "Likely OK · Unverified".

### 9.8 Export naming

`examShort` = the exam name cut at the first `/` or ` (`, every run of non-alphanumeric characters replaced by one `-`, trimmed of `-`
(`IBPS PO / MT` → `IBPS-PO`). File name: `filename ?? docType`-based: if the slot has `filename`, exactly `<filename>.jpg`; otherwise
`<docType>_<examShort>_<W>x<H>_<K>kb.jpg` with `K = round(bytes / 1024)`.
**PDF** (§6.6): `<filename>.pdf` when the slot has `filename`, otherwise `<docType>_<examShort>_<K>kb.pdf` (no dimensions).

### 9.9 Conformance case keys (`fixtures/cases.json`)

A fit case names either `preset` + `doc`, or an inline `spec` (a full document object, used for modes no real preset has, e.g. `exact`).
`crop {x,y,w,h}` is in _original source_ pixels (a decoder that downsamples scales it by `decodedWidth/sourceWidth` and
`decodedHeight/sourceHeight`, `round`ed, then clamped into the raster); `pipeline` is absent (photo kinds: crop+fit; other kinds: pad-to-aspect+fit),
`signature_cleanup`, `thumb_cleanup` or `document_cleanup`. `expect` keys, checked on the **re-inspected written bytes**:
`format: "jpeg_baseline"` (JPEG, SOF0), `color` (`rgb`), `kb_min`/`kb_max` (inclusive, KB = 1024), `width`/`height` (equal, unless
`allow_upscaled_preferred`: then the aspect must match within 1 px of rounding and the size lie in [1×, 2×]), `w_range`/`h_range`/`aspect_range`
(inclusive), `aspect` ± `aspect_tol`, `background_mean_min` (median luma of the whole image ≥ value), `ink_pixels_min_pct` (% of pixels with luma < 128 ≥ value),
`exif: "none"` (no APP1), `dpi` (JFIF units 1 and both densities equal), `export_filename` (§9.8), `decodes` (re-decodes without error),
`max_ms_midrange` (measured by the benchmark, not asserted in unit tests). Other sections: `inspect_cases`, `decode_cases`, `patch_cases`,
`geometry_cases`, `crop_cases`, `match_cases`, `match_note_cases`, `save_all_cases`, `checker_cases`, `kit_size_cases`, `kit_history_cases`, `kit_id_cases`, `search_cases`, and the PDF sections
of §9.10 (their keys are documented inline in the file).
`thumb_preservation_cases` draw a grey print parametrically: paper luma `round(left + (right − left)·x/(W−1))`; inside the
`print` ellipse `(cx, cy, rx, ry)` a pixel is a ridge when `⌊2d/period⌋` is even, `d` = distance to the centre with `y` scaled
by `rx/ry`, coloured `ridge` luma, or `rim` luma beyond normalised radius `rim[0]`; the optional `core` ellipse is solid.
Expectations: the output box of pixels `< visible_below` matches the source ink box (`< paper − ink_delta`) within
`box_tolerance` (nothing clipped); the core shrunk to 70 % has mean `≤ core_max_mean`; no pixel `< border_clear_above` lies
in the outer `border` pixels; at least `white_min_fraction` of the output is `≥ 250`; `ink_min` pixels are `< ink_below`.

### 9.10 PDF engine numbers (§6)

Units are PDF points (1/72 in), origin top-left, y down, as `double`s; the formulas below are evaluated in the order
written so all three implementations get the same bits. `A4 = 595 × 842`, `M = 0.05 · 595` (29.75), `mm = 72 / 25.4`.
`round` is §9.1 (half up). Pixel sizes use `k` = output pixels per source pixel.

- **Image page** (prepared raster `w × h`): page `PW × PH = 595 × 842` if `w ≤ h`, else `842 × 595`. `bw = PW − 2·M`,
  `bh = PH − 2·M`, `s = min(bw / w, bh / h)`, `rw = w · s`, `rh = h · s`, `rx = (PW − rw) / 2`, `ry = (PH − rh) / 2`.
- **ID card page**: `595 × 842`; card box `CW = 85.6 · mm`, `CH = 54 · mm`, gap `G = 15 · mm`; box `x = (595 − CW) / 2`,
  front `y = (842 − (2 · CH + G)) / 2`, back `y = front y + CH + G`. Each image is contained in its box and centred:
  `s = min(CW / w, CH / h)`, `rw = w · s`, `rh = h · s`, `rx = box x + (CW − rw) / 2`, `ry = box y + (CH − rh) / 2`.
- **Pixels of a placed image** at DPI `d`: `k = s · d / 72`. `k ≥ 1` → `w × h` (never upscaled). Otherwise by the long side:
  `w ≥ h` → `pw = max(1, round(w · k))`, `ph = max(1, round(h · pw / w))`; else `ph = max(1, round(h · k))`,
  `pw = max(1, round(w · ph / h))`.
- **PDF page** (size `pw_pt × ph_pt` as the platform renderer reports the displayed page): the output page has the same size and one image
  covering it (`rx = ry = 0`). `k = (d / 72) · min(1, 842 / max(pw_pt, ph_pt))`; pixels `max(1, round(pw_pt · k)) × max(1, round(ph_pt · k))`.
  Rendered on white, scaled to exactly those pixels.
- **Page cleanup** (image sources, `cleanup` on), on the prepared raster (long side ≤ 1600): `G` = §9.5 thumb steps 1–4 on
  its luma `L` (candidates, paper-only background, normalisation, tone). `grayscale` on → `R = G = B = G`; off → each channel
  `c' = min(255, (c · G + L / 2) / max(L, 1))` (integer division): the paper turns white and ink, photos and seals keep
  their colour and stay dark. Conformance: `page_cleanup_cases`, parametric pages drawn in this order: paper grey
  `round(left + (right − left)·x/(W−1))`, `text` bars `[x, y, w, h, luma]`, the `block` `[x, y, w, h, luma]`, the `stamp`
  disc `[cx, cy, r, [R, G, B]]` (`dx² + dy² ≤ r²`). Measured on the output: text = the bars' pixels (mean luma
  `≤ text_max_mean`); block = the block shrunk by 4 px (mean luma within `[block_min_mean, block_max_mean]`); stamp =
  the disc of radius `r − 4` (`stamp_gray`: every pixel's `max − min` channel `≤ 2`; `stamp_red_min`: mean `R − G` at
  least that); paper = pixels more than 6 px outside every bar, the block and the stamp's bounding square (at least
  `paper_white_min_fraction` of them with luma `≥ 250`).
- **Numbers in the PDF** `num(v)`: `c = floor(v · 100 + 0.5)`; the integer part `c / 100`, then nothing if `c % 100 = 0`,
  `.D` if `c % 10 = 0` (`D = c % 100 / 10`), else `.DD` (two digits). `29.75`, `30.5`, `595`.
- **Writer** (bytes, ASCII except the second header line): `%PDF-1.4\n%` `E2 E3 CF D3` `\n`, then objects numbered from 1, each
  `<n> 0 obj\n<body>\nendobj\n`: 1 `<< /Type /Catalog /Pages 2 0 R >>`; 2 `<< /Type /Pages /Kids [<p₁> 0 R <p₂> 0 R …] /Count <N> >>`;
  then per page in order the page object, its content stream, then its images (Im1, Im2 in placement order):
  page `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 <num PW> <num PH>] /Resources << /XObject << /Im1 <i₁> 0 R /Im2 <i₂> 0 R >> >> /Contents <c> 0 R >>`;
  content data = per image `q <num rw> 0 0 <num rh> <num rx> <num (PH − ry − rh)> cm /Im<j> Do Q\n`, body
  `<< /Length <len> >>\nstream\n<data>\nendstream`; image body `<< /Type /XObject /Subtype /Image /Width <pw> /Height <ph>
  /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length <len> >>\nstream\n<jpeg>\nendstream` (one line,
  single spaces); optional padding object last: `<< /Length <P> >>\nstream\n<P spaces>\nendstream`. Then at offset `X`:
  `xref\n0 <n+1>\n0000000000 65535 f \n`, one `<offset, 10 digits> 00000 n \n` per object, and
  `trailer\n<< /Size <n+1> /Root 1 0 R >>\nstartxref\n<X>\n%%EOF\n`. Conformance `pdf_writer_cases`: fixture JPEGs placed by
  the layouts above → exact `size` and `sha256` (reference `spec/tools/pdf.py`).
- **Pad** to `S` bytes: none if the PDF is already `≥ S` bytes; otherwise the smallest `P ≥ 0` whose padded PDF is `≥ S`
  bytes (exactly `S`, or `S + 1` where the length's digit count grows).
- **Fit** (`pdf_fit_cases`): window, `goal` and `T'` as §9.4 (null `max` → `UNKNOWN_LIMIT`). `size(d, q)` = written PDF
  bytes with every page at DPI `d` and quality `q`. `grid(d)` = `[85, 75, 65, 55, 45, 40]`, and for `d = 100`
  `[85, 75, 65, 55, 45, 40, 35, 30, 25, 20]`. For `d` in `[200, 150, 120, 100]`: if `size(d, last of grid(d)) ≤ goal.hi · 1024`,
  choose the first `q` of `grid(d)` with `size(d, q) ≤ goal.hi · 1024` and stop. None → `CANNOT_REACH`.
  If the window has a minimum (`min > 0`) and `size(d, q) < goal.lo · 1024` → pad to `round(T' · 1024)`; with no
  minimum a small PDF is never padded (unlike §9.4: a Tools limit is only a maximum). Strategy: `pad` when padded, else `quality` at 200 DPI and
  `lower_dpi` below. An implementation may stop measuring a DPI as soon as its lowest-quality JPEGs alone exceed `goal.hi`.
- **Job checks**, in order: no sources (or an ID card without exactly two pictures) → `NO_PAGES`; an encrypted PDF →
  `ENCRYPTED`; a PDF the renderer cannot open or with no pages → `UNREADABLE`; more than 20 pages → `TOO_MANY_PAGES`; the
  window (`UNKNOWN_LIMIT`); the unchanged rule (§6.1); then the pictures are prepared (one that does not decode →
  `UNREADABLE`) and the fit (`CANNOT_REACH`).
  Conformance `pdf_job_cases` runs real sources (fixture images and the PDFs in `fixtures/pdfs/`) through each platform's
  renderer and encoder and checks the written bytes: Inspector `pdf`, `pages`, `imageOnly`, not encrypted, KB in
  `[kb_min, kb_max]`, the strategy, or the error.

## 10. Exam search and browse (Home, PRD F1)

A pure function on both platforms (`ExamSearch`) over the presets bundle. Conformance: `search_cases`, whose expected results are
computed by the reference implementation `spec/tools/exam_search.py` (a third, independent implementation).

**Data** (all in the signed bundle, so search improves with a presets update, never with an app release):
`exam.aliases` (optional: other spellings and Hindi forms, e.g. `आईबीपीएस पीओ`, `bank po`), `bundle.categories[<category>].aliases`
(e.g. banking: `bank`, `बैंक`) and `bundle.popular` (exam ids, most popular first; Remote Config `popular_exam_order` may override it
from Phase 4). Older bundles without these fields behave as if they were empty.

**Visible exams.** `status = active`, and `confidence = low` only when the "Show unverified exams" setting is on. The setting is
**on by default** (an unverified exam is listed with its "Unverified — check notice" badge rather than missing); users can turn it off.

**Normalisation `norm(s)` → tokens.** (1) Unicode NFKC. (2) Lower-case every scalar with the Unicode default mapping (no locale rules).
(3) Delete U+093C (Devanagari nukta), U+200C and U+200D. (4) Every scalar that is not a letter (`L*`), mark (`M*`) or decimal digit (`Nd`)
becomes a space; Devanagari vowel signs and virama are marks, so Hindi words stay whole. (5) Split on spaces, dropping empty tokens.
Lengths and comparisons below are in Unicode scalars; `_` in ids is punctuation, so `state_psc` → `state psc`.

**Index of an exam:** the tokens of `name`, `body`, `id`, `category`, each category alias and each exam alias, plus one _joined_
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

## 11. Custom size (PRD F9)

For a form that is not in the catalogue. The user says what the file is and copies the limits from the form's notice; the app
then runs the **same** photo or ink flow (§2, §3) against a synthetic slot built from those limits. There is no second pipeline.

**Input.** `doc` ∈ `photo`, `signature`, `thumb_impression`, `handwritten_declaration` (shown as Photo, Signature, Thumb
impression, Handwritten document); it picks the flow (photo flow for `photo`, ink flow with the §9.5 variant otherwise) and the
match kind (§4). `width`, `height`: both or neither, in pixels or in centimetres (`px = round(cm × dpi / 2.54)`, half up; a
pixel value with decimals is rounded the same way). Neither = keep the picture's own size. `min_kb` (optional) and `max_kb`
(required): whole KB. `dpi`: whole number, default 200 when empty.

**Validation**, codes in this order (all that apply):
`max_kb_missing` (no max) · `max_kb_range` (max outside 1–5120) · `min_kb_range` (min given and not `0 ≤ min < max`) ·
`size_pair` (only one of width/height) · `width_range` / `height_range` (outside 16–2000 px after conversion; 2000 keeps the
§1.1 decode cap at 4000 px for low-end phones) · `dpi_range` (outside 72–1200). Any code blocks Continue and shows next to its field.

**Slot.** `type = doc`, `formats = [jpg]`, `size_kb = {min: min_kb if > 0 else null, max: max_kb, target: null}`,
`dimensions = {mode: exact, width, height}` when given, else `{mode: none}`, `dpi`, no `filename`, no `declaration_text`.
Exam id `custom`, exam name `Custom` (not translated: it names the file and the folder), so §9.8 gives
`signature_Custom_140x60_16kb.jpg` and Android saves to `Downloads/ScanFit/Custom/`.

**Flow differences from an exam slot.** No retained draft, no Saved/Ready status (there is no checklist), no handwriting
confirmation (the CAPITAL-letters rule belongs to exams), and the verdict reads "Meets your size" / "Doesn't meet your size"
(EXACT or ACCEPTED for the synthetic slot, §9.7). The review's match note lists the catalogue exams that accept the file (§4).

**Canonical text** (navigation argument and recent sizes): `<doc>;<W>x<H>;<min>-<max>;<dpi>`, with `-` for no size and `0` for
no minimum: `signature;140x60;10-20;200`, `photo;-;0-50;200`. Parsing accepts exactly this form for a size that passes the
validation above, else nothing. **Recent sizes:** the last 5 distinct texts, newest first, kept on the device; Continue moves
its text to the front. Conformance: `custom_size_cases` (`fields` → codes, or slot summary + text; `parse` text → canonical text or null),
computed by `spec/tools/custom_size.py`.

## 12. Checker (PRD F10)

"Will this file be accepted?" for a file the user already has. Nothing is changed unless the user asks for a fix.

**Input.** One file from the gallery or Files, read with the usual 64 MiB bound, inspected by §5 with its file name
(the name feeds `EXTENSION_MISMATCH`). The file's own checks (§5 context-free issues) show at once.

**What is this file?** The user picks the `DocKind` (Photo, Signature, Thumb, Fingers, Declaration, PDF document); the
app never guesses. With a kind, the match note (§4, §9.7) lists the exams that accept the file as it is.

**Which exam is it for?** A list of (active exam, slot) pairs whose slot type matches the kind (§4 mapping; an exam with
two matching slots appears twice, e.g. NEET left and right hand fingers), in popularity order then name, with the
§10 search over exam names and aliases. Choosing one evaluates the slot (§9.7) and shows the technical chips
(`review_check_cases` mapping), the file's issues followed by the slot's issues, and a status
(`CheckerStatus.of`, conformance `checker_cases`, `spec/tools/checker.py`):

1. verdict `UNKNOWN` → `unknown` ("Spec not verified yet");
2. verdict EXACT or ACCEPTED → `ok`, or `likely_ok` when the exam is unverified (rule 6: never "valid");
3. otherwise, if the file is an image the app can decode (`jpeg`, `png`, `webp`, `heic`) and the slot accepts `jpg`/`jpeg`
   → `fix`: offer "Make it fit for <exam>";
4. otherwise, if the slot accepts `pdf` and the file is a decodable image or a `pdf` that is not encrypted → `fix`: "Make
   it fit" makes a PDF for the slot (§6.6: a one-source `exam` job, grayscale and cleanup on);
5. otherwise → `no_fix` (an encrypted PDF, an undecodable file, or a PDF for an image slot).

**Make it fit** for a PDF slot runs §6 and shows the PDF result (first page, KB, pages, verdict) with Save and Share.
For an image slot it decodes the file (§1.1, oriented upright, EXIF dropped), then runs the plain §1 pipeline for the slot: photo
kinds crop centred to the slot aspect, other kinds pad with white; no cleanup, face check or strip. The result shows like a
review (image, chips, slot verdict, match note) with Save and Share (§1.6, §1.6.2). Save uses the slot's kind and §9.8 name
and records Saved for that exam slot like any verified export; an exam signature still needs the one-time handwriting
tick. The original file is never modified.

## 13. My Kit: originals, reuse and export history (PRD F11)

My Kit has three views behind a native segmented control: **Prepared** (the retained drafts, §1.6.1), **Originals** and
**History**, in that order, Prepared first. Each view has its own loading, empty and retryable error state and reloads on
tab entry, app foreground and store changes (obsolete loads are cancelled). Nothing here leaves the device (rule 2).

### 13.1 Originals

**Saving.** A new photo or ink review shows a secondary `kit.save_original` ("Save to My Kit") under Share. A restored
review (§1.6.1, no source kept) and the Checker do not. It does not depend on the fit verdict: the original is not the
fitted file. Custom size reviews have it too. The stored picture is the flow's **working source**: the upright raster
the crop step used (§1.1 decode, after the user's 90° turns), not the crop and without effects. If its long side is over
`2400` px it is resized: long side → 2400, other side → `max(1, round_half_up(other · 2400 / long))` (`kit_size_cases`).
It is encoded as a baseline JPEG at quality 92 and passed through the §1.5 patcher (RGB, JFIF, no EXIF/GPS/XMP). Its
kind is the slot's `DocKind` (`DocKind.of(type)`; never `PDF_DOCUMENT`). After a verified save the button reads
`kit.saved_original` and stays disabled until the flow gets a new source. A full Kit (`30` originals) writes nothing and
says `kit.full`; a storage failure says `kit.save_failed` and keeps the button for a retry. Saving never changes the review.

**Keeping.** Originals stay until the user deletes them (decision 2026-10-15; Phase 4 may limit free users to 30 days).
Delete is on the original's screen, behind a confirmation (`kit.delete_title`, `kit.delete_body`: files already made are
not affected).

**Storage.** One app-private root excluded from backup (Android `noBackupFilesDir/kit`, iOS Application Support `kit`
with `isExcludedFromBackup`): `index.json` (`version: 1`, metadata only: originals `id, kind, created_at_ms, width,
height, size`; history entries, §13.3), and the bytes in `originals/<id>.jpg` and `exports/<id>.jpg`. Ids are generated by
the app as lowercase UUID v4 text and must match `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`
before any path is built from them (`kit_id_cases`); routes carry ids, never paths or bytes. A write stages the bytes in a
temporary file, re-reads and verifies them, renames them into place, then replaces the index the same way, all under one
lock. Loading drops index entries with a bad id, unknown kind or document type, or impossible numbers, and removes
temporary files and blobs that no entry names (process death between the two renames). Symlinks are rejected. A corrupt
or unsupported index is replaced by an empty one, and its blobs are removed as orphans.

**Reading** an original (thumbnail, detail, reuse) re-inspects its bytes (§5): JPEG, SOF0, RGB with 3 components, no
EXIF/GPS/XMP, at most 16 MiB, dimensions equal to the entry's and long side at most 2400. Failure removes the entry and
says `kit.original_missing`. The list is grouped by kind in the order Photo, Signature, Thumb, Fingers, Declaration,
newest first in each group; a row shows a thumbnail (decoded off the main thread at no more than twice its display
size), the kind and the date saved. The view model never holds full-size originals.

### 13.2 Use for exam…

The original's screen offers `kit.use_for_exam`: the Checker's list (§12) of (active exam, slot) pairs for the original's
kind, popularity then name, with search and the unverified badge. Choosing one opens that slot's photo or ink flow with
the original as its source. The flow skips the retained-draft restore and the picker, reads the original (§13.1 checks),
decodes it by §1.1 with the slot's own cap and continues exactly as after a pick: photo face detection and framing, ink
crop. An unreadable original returns to the source step with the open-failed message. Back from the crop step goes to the
source step, where the user may pick another picture. Everything after that is the normal flow (retained draft, Save,
Saved, Share). Save to My Kit shows as already saved for the original's own source. Done returns to the
original's screen in My Kit, so the same original can be used for the next exam.

### 13.3 Export history

Every verified, published export (§1.6: review Save, Save all, Checker save, Custom size, PDF tools and PDF slots §6.5)
appends one entry after the written file passes verification: `id, created_at_ms, exam_id, exam_name, doc_type, kind,
file_name (§9.8), size, width, height`, plus `format` (`jpg`, the default when absent, or `pdf`) and, for a PDF, `pages`
(≥ 1; `width` and `height` are 0). Custom size entries carry `exam_id` `custom`, PDF tools `pdf`. A private copy of the
exact verified bytes is written to `exports/<id>.jpg` (`exports/<id>.pdf` for a PDF) so the file can be shared again; a
PDF copy's read check is: format `pdf`, not encrypted, the entry's size (the list row reads `182 KB · 3 pages · PDF`). Recording is best effort: it never changes the export result, the
Saved marker or the UI of the save, and a failure is not logged with names.

**Retention** (`ExportHistory.prune(entries, now, max_entries)`, `kit_history_cases`): entries are ordered newest first
(`created_at_ms` descending; equal times keep the later-recorded first); the first `max_entries` are kept (`100` in the
apps); an entry's copy is **shareable** when `0 <= now − created_at_ms < 30 days` (`2,592,000,000` ms), the same window
as drafts. Dropped entries and copies that are no longer shareable are deleted on load (best effort); the entry itself
stays until it falls out of the newest 100.

**List.** Newest first: document label, exam name, `34 KB · 200×230` and the date. A shareable entry has Share: the copy
is re-read and must match the entry's size and dimensions and pass the read check of §13.1, then it is staged and shared
under its recorded file name exactly as §1.6.2 (byte equality before the sheet; never records Saved). A copy that is gone
or older than 30 days shows `kit.history_expired` instead. `kit.history_clear` (with a confirmation) deletes every entry
and copy; the checklist's Saved markers are not history and stay.

Conformance: `kit_size_cases` (`width`, `height` → stored size), `kit_history_cases` (`entries` in recorded order, `now_ms`,
`max_entries` → `kept` ids in order and `shareable` ids), `kit_id_cases` (`id` → `valid`). Reference: `spec/tools/kit.py`.
