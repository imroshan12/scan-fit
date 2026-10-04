# UI_UX.md — production-grade, native on each platform

## 1. Principles

1. **Task first, not tool first.** Home asks "What are you applying for?", not "Resize / Compress / Convert".
2. **Show certainty.** Every result shows a green verdict backed by numbers (`34 KB · 200×230 · JPG ✓`)
   and the exam match note. Anxiety is the real problem we solve.
3. **One primary action per screen**, pinned to the bottom within thumb reach.
4. **Native idioms.** Android: Material 3, navigation bar, predictive back, edge-to-edge.
   iOS: TabView, NavigationStack, sheets with detents, SF Symbols, standard swipe-back. Same
   information architecture, platform-true controls. Never ship an iOS app that looks Android, or the reverse.
5. **Fast by default.** Work happens off the main thread, and every tap gets feedback within 100 ms
   (pressed state or haptic), with progress for anything over 300 ms.
6. **Bilingual from day one.** Hindi strings are ~30% longer, so no fixed-width text containers.

## 2. Information architecture

```
Tabs:  Home  ·  My Kit  ·  Tools  ·  Settings
Home ─ search exams ─ My exams (pinned) ─ Popular now (Remote Config) ─ "Fix a file" (Checker) card
  └ Exam screen (checklist) ─ Photo flow | Signature flow | Thumb flow | Declaration flow | Certificates
                            ─ "Captured live on portal" row → Live coach
                            ─ Save all
My Kit ─ Photo · Signature · Thumb · Declaration originals ─ "Use for exam…" ─ Export history
Tools ─ Custom resize ─ Checker ─ PDF: images→PDF, compress, merge, ID card ─ Scan ─ Printable guide sheet ─ Live coach
Settings ─ Language ─ Pro ─ Presets version + "Check for updates" ─ Show unverified exams ─ Privacy ─ Help/Report a problem
```

## 3. Key screens (states: loading · empty · content · error · offline)

**Home.** Large search field ("Search 55+ exams — IBPS, SSC, NEET…", Hindi aliases indexed, e.g.
"बैंक" → banking). Category chips. Pinned exams show a progress ring (3/4 files ready).
Empty state for first launch: 3 popular exam cards + "Not listed? Use Custom size".

**Exam checklist.** Header: exam name, body, "Specs verified 30 Sep 2026 · Source" link, and a
confidence badge. Rows per document: icon, name, requirement summary (`20–50 KB · 200×230`), and a
status: `Not started` → `Ready ✓ 34 KB` → `Saved`. The live-photo row has a "Practise" button.
Bottom CTA: "Make all files" (Pro, from My Kit) or "Save all (4)". Special rules appear in an
expandable "Before you upload" card (e.g. "Declaration must be in English, not CAPITALS").

**Capture/crop.** Full-bleed camera with a dashed overlay at the exact aspect (the signature box
is 7:3 for 140×60). Tips carousel ("Use white paper · daylight · no shadow"). After capture: crop
with a locked aspect, pinch-zoom, rotate 90°, corner handles for 4-point perspective (ink docs).

**Review (the money screen).**

```
┌───────────────────────────────┐
│   [ result image, zoomable ]  │   before/after toggle (long-press shows the original)
│   34 KB · 200×230 · JPG ✓     │   chips turn green with a 150 ms check animation
│ ✓ Accepted by 14 exams  ›     │   match note (ALGORITHMS §4)
│ ⚠ 1 quick fix   [Fix]         │
│ [ White background ○ ] [ Name & date ○ ]   (photo)
│ [ Crisp black ● ] [ Darker ink ─●── ]      (ink docs)
│      [  Save to Downloads  ]  │   primary
│      Share · Save to My Kit   │   secondary
└───────────────────────────────┘
```

Photo review currently saves one file: Save to Downloads (Android API 29+) or Save to Files (iOS / older Android).
During saving, edits and back are disabled. Picker cancellation leaves review unchanged; failure shows a retryable error.
Only a re-opened, verified destination shows Saved and changes the primary action to Done; editing again returns to Save.
The exam's Saved row persists across launches. iOS lets the user choose the Files folder.

**Checker.** Drop zone / picker → a result card listing each check with a pass/fail icon, human
text, and a Fix button. Then the "Which exam is this for?" picker → per-exam verdict.

**Custom resize.** Inputs: KB min/max (steppers + text), dimensions (px or cm+DPI toggle),
format, aspect lock. Live preview, estimated size, and the match note updating live.

**Paywall.** One screen: 3 benefit rows with icons, a yearly/lifetime segmented choice
(lifetime labelled "Pay once"), price in local currency from the store, "Restore purchases",
plain-language terms. Never shown mid-flow; triggered at the free export limit (with the
rewarded-ad option side by side), on Pro features, or from Settings.

## 4. Design system (generated from `spec/tokens/tokens.json`)

- **Colour:** brand primary (trustworthy deep indigo, not government saffron/green, to avoid any
  "official" look), success green, warning amber, error red. Neutral 50–950. All semantic
  roles have light and dark values. Contrast ≥ 4.5:1 for text, ≥ 3:1 for icons/borders.
- **Type:** Android Roboto Flex (+ Noto Sans Devanagari fallback); iOS SF Pro (+ system
  Devanagari). Scale: display 32 / title 22 / headline 18 / body 16 / label 14 / caption 12.
  Size figures (`34 KB`) use tabular numbers.
- **Spacing:** 4-pt grid (4, 8, 12, 16, 24, 32). Screen margin 16. Touch targets ≥ 48 dp / 44 pt.
- **Shape:** cards 16, buttons 12 (Android) / platform default (iOS), chips full.
- **Components** (both platforms, same names): `VerdictChip`, `MatchNote`, `DocRow`,
  `SpecSummary`, `ConfidenceBadge`, `PrimaryBottomBar`, `BeforeAfterImage`, `KbSlider`,
  `IssueRow`, `EmptyState`, `ProgressRing`. Each gets previews in light/dark/large-font/Hindi (no
  screenshot tests: docs/TESTING.md §1).

## 5. Motion and feedback

- Durations: 150 ms micro (chips, toggles), 250 ms standard (sheets, cards), 350 ms emphasised
  (screen transitions). Standard/emphasised easing per platform. Respect "Reduce motion".
- Shared element: the thumbnail in the checklist → the review image.
- Processing: a determinate step list for ink cleanup ("Straightening → Cleaning paper → Fitting to
  16 KB"). Never a bare spinner over 1 s.
- Haptics: success (verified save), warning (near miss), selection ticks on the KB slider at
  window edges. Android `HapticFeedbackConstants.CONFIRM/REJECT`; iOS `.sensoryFeedback`.
- Success moment: after "Save all", a green check burst + "4 files saved to Downloads/ScanFit/IBPS PO"
  with an "Open folder" action.

## 6. Accessibility

TalkBack/VoiceOver labels on every control. Verdict chips are read as "34 kilobytes, within 20 to
50, passes". Dynamic Type / font scale up to 200% without truncating CTAs (layouts reflow to a
single column). Don't rely on colour alone: icons + text on every verdict. The camera overlay has a
"Describe" button that speaks the framing hints. The coach reads checks aloud (optional).

## 7. Copy tone

Short, calm, specific. "Too big for IBPS: 58 KB (max 50). Fix?" not "Error: invalid file size".
Hindi copy is written, not machine-translated, for all primary flows. Machine-drafted strings are
marked `TODO_HI` until reviewed by a native speaker.

## 8. Quality bar before launch

- Every screen passes the state matrix (loading/empty/content/error/offline), checked in previews and by hand.
- 60 fps on a mid device and no dropped frames >2% on low-end during list scroll and transitions
  (Android Macrobenchmark `FrameTimingMetric`; iOS Instruments hitches).
- Five-person hallway test with real aspirants: each completes an IBPS kit unaided in < 5 min.
