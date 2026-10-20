# UI_UX.md — production-grade, native on each platform

## 1. Principles

1. **Editor first, exams built in** (user decision 2026-10-20, replacing "task first"). Home asks "What would you like to
   do?" with large tool cards; exam forms are one of them, and the exam rules stay the app's strength inside every tool.
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
First launch ─ Welcome + sign-in (Continue with Google · Continue with Apple on iPhone) ─ then Home   (2026-10-20)
Tabs:  Home  ·  My Kit  ·  Settings          (the Tools tab was folded into Home, 2026-10-20)
Home ─ tool cards: Exam forms · Resize photo · Signature · Compress PDF · Images to PDF · Merge PDFs · ID card ·
       Check a file · Thumb impression · Handwritten page ─ My exams strip (pinned) ─ Coming soon (Scan, guide sheet, coach)
  ├ Exam forms ─ search exams ─ category chips ─ My exams ─ Popular now ─ specs status ─ Custom size / Checker links
  └ Exam screen (checklist) ─ Photo flow | Signature flow | Thumb flow | Declaration flow | Certificates
                            ─ "Captured live on portal" row → Live coach
                            ─ Save all
My Kit ─ Photo · Signature · Thumb · Declaration originals ─ "Use for exam…" ─ Export history
Settings ─ Account (email · Sign out · Delete account) ─ Pro (status · free saves left · Restore) ─ Language ─
           Presets version + "Check for updates" ─ Show unverified exams ─ Privacy (analytics opt-out) ─ Help/Report a problem
```

## 3. Key screens (states: loading · empty · content · error · offline)

**Home** (redesigned 2026-10-20: editor first). A small "ScanFit" wordmark, the title "What would you like to do?" and a
one-line subtitle, then a grid of large tool cards: two columns (one column at the largest text sizes or narrow screens),
each card a soft tinted container with a round icon tile in its accent colour, a title and a one-line description; every
card in a row is as tall as the tallest. Order: Exam forms, Resize photo, Signature, Compress a PDF, Images to PDF, Merge
PDFs, ID card on one page, Check a file, Thumb impression, Handwritten page. Accents (tokens `photo`, `ink`, `pdf`; exams
use primary, the Checker success): Resize photo = photo; Signature, Thumb, Handwritten page = ink; the four PDF tools = pdf.
Resize photo / Signature / Thumb / Handwritten page open Custom size with that kind chosen. Under the grid: "My exams" as
a horizontal strip of pinned exams (only when there are any), then "Coming soon" with muted cards (Scan, Printable guide
sheet, Live photo coach). No search field on Home.

**Exam forms** (the former Home list). Large search field ("Search 55+ exams — IBPS, SSC, NEET…", Hindi aliases indexed,
e.g. "बैंक" → banking). Category chips. My exams, Popular now, the specs status card, and the "Not listed? Use Custom
size." / "Already have a file? Check it." links.

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

Photo and ink review save one file: Save to Downloads (Android API 29+) or Save to Files (iOS / older Android). **Share**
(2026-10-13, ALGORITHMS 1.6.2) is a text button under Save with the same gate; it opens the system share sheet with exactly
the review bytes under the export name and never marks the row Saved. A staging failure shows `export.share_failed`.

**Save all** (exam checklist bottom bar, 2026-10-13): "Save all (N)" counts the Ready rows. An exam signature still
waiting for the handwriting tick is held, with a one-line note under the button. iOS opens one Files picker for every
file (one folder); Android 10+ writes to `Downloads/Mr Doc/<Exam>/` with no prompt and shows progress "Saving… 2/4";
Android 8–9 has no Save all (one picker per file would be worse than saving each row). The result replaces the button:
all saved → "4 files saved to …" with Open folder (Android: the system Downloads view) and Close; some failed →
"Saved 3 of 4. Try again for the rest." with Retry (re-plans, so saved rows are not written again) and Close.
During saving, edits and back are disabled. Picker cancellation leaves review unchanged; failure shows a retryable error.
Only a re-opened, verified destination shows Saved and changes the primary action to Done; editing again returns to Save.
The exam's Saved row persists across launches. iOS lets the user choose the Files folder.

Photo and ink outputs are also automatically retained privately for 30 days after verification. On return to the
checklist, an available unsaved file shows Ready · KB; historical Saved takes precedence. Tapping its row after relaunch
opens a read-only review with normal Save and Replace file actions. No Before image or effect controls appear because
only final bytes, not the crop/options, were retained. Replace/Back go to source selection without deleting the previous
good draft. Restored signatures still require the one-time handwriting tick. A retention failure warns that the file
cannot be kept for later but leaves immediate Save available. Missing/invalid/expired drafts never show Ready.

Photo and ink review now use a native Before/After selector (After initially) and hold-to-show-Before. Before is the
oriented selected crop, before effects, not the full captured image. Both fit inside the same 280 dp/pt letterboxed frame.
Comparison never changes options, Saved or export bytes. Three wrapping KB/dimensions/JPG chips use icons and spoken
pass/fail labels backed by the re-inspected output. Pending edits and failures hide old output/chips. Technical passes
do not certify an unverified preset; the separate "Likely OK · Unverified" verdict stays. See ALGORITHMS §4.

**PDF maker** (ALGORITHMS §6, built 2026-10-15). Home's PDF cards open Images to PDF, Compress a PDF, Merge PDFs and ID card
on one page; an exam's PDF row (e.g. JEE Main "Class 10 certificate") opens the same
screen for that slot. One scrolling screen, top to bottom:
1. *Intro* — the exam name and window (`50–300 KB · PDF`) for a slot, then one line on what to add.
2. *Pages / Files* — each file with a 56 dp/pt thumbnail, "Photo 2" or "PDF", `3 pages · 812 KB`; move up/down and remove
   (icon buttons with spoken labels); pictures also have Crop (free rectangle, the ink flow's editor) and Rotate. ID card:
   Front and Back slots, Back offered once Front is there. Add buttons: gallery (multi-select), Files (images and/or
   PDFs as the tool accepts), camera. Unreadable, password-protected and over-20-page additions say so in place.
3. *Options* — "Clean up photos" (pictures only) and "Black and white" (both on by default, user decision 2026-10-15),
   each with a one-line hint; Tools add Max KB (number pad, quick picks 100 · 200 · 300 · 500, default 200).
4. *Result* — the first page, `182 KB · 3 pages · 150 DPI`, the slot verdict ("likely OK" for unverified) or "Fits 200 KB",
   and one note: text is now an image / already fits, nothing changed / made bigger to reach the minimum.
Bottom bar: Make PDF (with what is missing underneath), progress "Fitting page 2 of 5…" while working (Back cancels),
then Save (Downloads / Files; Done after a verified save) and Share. Any edit drops the result. Saving an exam slot
marks its row Saved; every verified PDF joins My Kit History (`182 KB · 3 pages · PDF`).

**Checker** (ALGORITHMS §12, built 2026-10-15). One scrolling screen, top to bottom:
1. *Pick* — "Choose from gallery" / "Choose from Files" (images and PDFs). Opening failures say so in place.
2. *This file* — format · KB · W×H, then the file's own problems (EXIF rotation, GPS, CMYK, progressive, wrong extension) as
   icon + text rows, or "No problems with the file itself." "Choose another file" restarts.
3. *What is this file?* — wrapping chips (Photo, Signature, Thumb impression, Fingers and thumb, Handwritten declaration, PDF
   document); the selected chip carries a tick. The app never guesses the kind.
4. *Which exam is it for?* — the match note for the file as it is, a search field, and every (exam, slot) of that kind:
   popular first, unverified exams with their badge.
5. *Result* — exam + slot with "Change exam" (system Back does the same), the KB/dimension/format chips, the slot verdict
   ("likely OK" for unverified) and each issue with its numbers ("Too big: 605 KB (maximum 20)."). When fixable:
   "Make it fit for <exam>"; for a PDF slot it makes a PDF from the picture or PDF (§6.6). Password-protected PDFs and
   undecodable files explain why there is no fix.
6. *Fixed copy* — the image, chips, verdict, match note, "Your original file is not changed.", the one-time handwriting tick
   for signatures, then a bottom bar with Save (Downloads / Files) and Share. After a verified save the button reads Done and
   returns to the exam list.

**My Kit** (ALGORITHMS §13, built 2026-10-15). A native segmented control under the title switches three views,
Prepared first; each has its own loading, empty and retryable error state and refreshes on entry, foreground and store
changes.
- *Prepared:* retained drafts in native exam sections, with a confidence badge, document label and rounded KB. A row
  opens that exam's retained read-only review; Done returns to Kit. Low-confidence exams stay listed even if hidden from
  Home. These files keep the 30-day retention policy.
- *Originals:* pictures the user kept with **Save to My Kit** (a text button under Share on new photo/ink reviews; not on
  restored reviews or the Checker), grouped by kind (Photo, Signature, Thumb, Fingers, Declaration), newest first, each
  with a 56 dp/pt thumbnail, the date saved and its size. Up to 30, kept until deleted (Phase 4 may give free users 30
  days). A row opens the original's screen: preview, date and size, a pinned **Use for exam…** button that opens a
  sheet with the Checker's exam list and search, and Delete (toolbar, with a confirmation). Choosing an exam opens that
  slot's flow straight at the crop step with the original; Done comes back to the original's screen.
- *Previews (2026-10-20, user request):* every Prepared and History row has a 56 dp/pt thumbnail (the file itself; a PDF's
  first page). Tapping a History row that is still kept opens a full preview (the image, or a PDF's pages one under
  another) with Share. On a review, Save to My Kit first shows the picture that will be kept, with Save to My Kit / Cancel.
- *History:* every verified save, newest first: document, exam (or "Custom size"), `34 KB · 200×230 · JPG` and the
  date. Share re-sends the private copy for 30 days, then the row says "No longer kept (30 days)". Clear history (toolbar,
  with a confirmation) deletes the list and copies, never the user's saved files or the checklist's Saved marks.

**Custom size** (built 2026-10-13, ALGORITHMS 11). Reached from Home's Resize photo / Signature / Thumb / Handwritten page cards
(that kind already chosen) and from Exam forms ("Not listed? Use Custom size.",
under the lists and under "no matches"). One scrolling form: "What are you making?" (Photo · Signature · Thumb impression ·
Handwritten document), Dimensions (Pixels/Centimetres segmented control, Width, Height, DPI; "= 276 × 354 px" while in
centimetres; empty = keep the picture's own size), File size (Min KB optional, Max KB required), Recent sizes (last 5,
one tap refills the form). Number pads with a keyboard bar (Next walks the fields, Done closes); Continue is hidden while
typing and pinned at the bottom otherwise. Errors appear under their field (icon + text) once the field has text, and all
of them after a failed Continue; nothing is shouted on first view. Continue opens the same photo or ink flow as an exam
slot; its review says "Meets your size", lists the catalogue exams that also accept the file, saves as `…_Custom_…jpg`,
and never touches the exam checklist. Deferred: format choice (always JPG), aspect lock, a live size estimate before
capture.

**Welcome + sign-in** (first launch, user decision 2026-10-20; PRD §6). One calm screen: the Mr Doc mark, one line on what
the app does, "Continue with Google" (both apps) and "Continue with Apple" (iPhone, required by Apple next to Google), a
privacy line ("Your photos and files never leave your phone. Your account keeps only your email and how many free saves
you've used."), privacy and terms links, and the non-affiliation disclaimer in small text. Offline: "You're offline. Mr Doc
works without internet; we'll ask you to sign in when you're back online." and Continue. Errors and a cancelled sign-in
stay on this screen with a retry. Where the gate sits (first launch or first save) is one setting, so it can move.

**Paywall** (ALGORITHMS §14.3). One full screen with a close button: title, 3 benefit rows with check icons, both
products side by side as cards ("Yearly · ₹99 a year", "Lifetime · ₹199 once"; stacked at large text), the store's own
localised price, "Yearly renews each year. Cancel anytime in Play Store / App Store settings", "Restore purchases",
privacy and terms links. Tapping a card starts the store purchase. States: loading, store unavailable (Retry), buying
(cards disabled), pending payment, failed, thanks (Done). Shown over the current screen only when a Save, Share or Save
all is blocked (the review and its draft stay as they were) or from Settings; never at launch. Reviews show "3 free
saves left" under Share once 5 or fewer remain. No ads anywhere.
Closing while the store is working does not cancel the purchase. If reopened before the result, purchase and Restore
remain disabled; a late result updates Pro but never replaces the new offer with the previous presentation's message.

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
- Success moment: after "Save all", a green check burst + "4 files saved to Downloads/Mr Doc/IBPS PO"
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
