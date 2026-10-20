# PRD — Mr Doc (code name ScanFit)

Store name: **Mr Doc** (user decision 2026-10-20). Code, modules and ids still say `scanfit`. Marketed as an everyday photo
and PDF editor, with exam and government forms built in (UI_UX §1).

**One line:** Get every upload for your exam form right the first time. Photo, signature, thumb,
declaration and certificates, fixed on your phone, offline.

## 1. Problem
Indian exam and government portals reject uploads over tiny spec differences (19 KB vs 20 KB, a
progressive JPEG, a signature in capitals, a missing name/date strip). Aspirants apply to many
exams a year, mostly from phones, often near deadlines. Today they use ad-heavy resizer apps,
contradictory web resizers, or a cyber café. Existing tools only resize. They don't help capture a
clean signature from paper, can't tell you which exams a file satisfies, and don't check a file
before you upload it.

## 2. Users
| Persona | Context | Needs |
|---|---|---|
| **Govt-job aspirant** (primary), 20–30, Tier 2/3 city, budget Android, Hindi/English | Applies to 5–15 exams/yr: IBPS, SBI, SSC, RRB, state PSCs | Exact files fast; reuse across exams; no mistakes |
| **Entrance aspirant** (secondary), 16–19 with a parent helping | JEE, NEET, CUET: one busy season a year | Finger/thumb impressions, postcard photo, certificate PDFs |
| **General user** (tertiary) | Any form needing "under 200 KB" | Scanner + compress to KB, ID card on one page |

Minors use the entrance flows. Collect no age data, show no ads (v1.0 has none, §6), keep analytics to the closed enum
set (ARCHITECTURE §9), and keep every document on the device. The sign-in account (§6) holds only email, name and the
save count.

## 3. Goals and success metrics (6 months after launch)
| Metric | Target |
|---|---|
| Upload success (self-reported "Portal accepted it?" prompt) | ≥ 97% yes |
| Time from "pick exam" to all files saved (IBPS 4-file kit) | median ≤ 3 min |
| D7 retention | ≥ 18% (seasonal product; D30 matters less than repeat per notification) |
| Free → Pro conversion | ≥ 5% of users who use all 20 free saves; ≥ 2.5% of monthly actives |
| Play rating | ≥ 4.5 with ≥ 1,000 ratings |
| Crash-free sessions / ANR rate | ≥ 99.7% / < 0.3% |

## 4. Scope by release

### v1.0 — Exam Kit (launch)
| # | Feature | Notes |
|---|---|---|
| F1 | **Exam catalogue** (55+ presets, search in EN/HI, categories, "My exams") | Data from signed CDN presets; low-confidence hidden behind a toggle |
| F2 | **Exam checklist screen**: every upload the exam needs, with status | Live-photo exams show "captured on portal" + link to the coach |
| F3 | **Photo flow**: pick/capture → face-aware crop → optional white background → optional name/date strip → fit | ALGORITHMS §2 |
| F4 | **Signature flow** (incl. UPSC triple): capture on paper → cleanup → trim → fit | §3; printable guide sheet |
| F5 | **Thumb / fingers flow** (IBPS LTI, NEET both hands) | §3 thumb variant |
| F6 | **Handwritten declaration helper**: shows exact text per exam, capture, cleanup, fit | Only for exams with verified `declaration_text` |
| F7 | **Make it fit**: fit-to-KB engine incl. the minimum-size strategy | §1 |
| F8 | **Exam match note on every edit** (the headline feature): after any export, custom resize or check, show which exams accept the file and one-tap fixes for near misses | §4; see §5 below |
| F9 | **Custom resize**: any KB window, px, cm@dpi, format; live match note | For forms not in the catalogue |
| F10 | **Pre-submit Checker**: open any file → format, KB, px, DPI, CMYK, progressive, EXIF → per-exam verdicts + fixes | §5 |
| F11 | **My Kit**: store cleaned originals once (photo, signature, thumb, declaration) → generate any exam's set in one tap | Originals in app storage only |
| F12 | **Save all**: exports to `Downloads/Mr Doc/<Exam>/` (Android) / Files (iOS) with correct names; share sheet | Verify-after-write §1.6 |
| F13 | **Certificate PDF tools**: images → PDF, compress PDF to KB, merge, **ID card on one page** | §6 |
| F14 | **Basic scan**: ML Kit Document Scanner / VisionKit → straight into F13 or F4–F6 | |
| F15 | **Live photo coach**: practise the SSC/UPSC/NTA live capture with real-time checks (moved to v1.1, LAUNCH_PLAN) | §7 |
| F16 | **Hindi + English UI** | All strings via spec/strings |
| F17 | **Printable guide sheet** (PDF): boxes at true scale for signature, thumb, declaration, triple signature, NEET fingers | Improves capture quality a lot |
| F18 | Preset OTA updates + "Spec updated" badges | Signed bundle, daily check |
| F19 | Monetisation: 20 free saves, then Pro (yearly + lifetime). No ads | §6 below |

### v1.1 — Full scanner (4–6 weeks after launch)
Scan library with folders; on-device OCR full-text search (ML Kit Latin + Devanagari; Vision on iOS);
auto-naming and tagging (Aadhaar, PAN, marksheet); **masked Aadhaar** export (OCR finds the 12-digit
number → masks the first 8); PDF password protect / split / reorder / sign; batch scanning;
backup to the user's **own** Google Drive (appDataFolder) / iCloud Drive; app lock (biometric).

### v1.2 — Stay ahead
Exam alerts: subscribe to an exam → push when its preset changes or its application window opens
(FCM topics; data in presets). Expiry reminders from scanned documents (insurance, PUC, licence).
Home-screen widget: "My Kit ready ✓".

### v2 — Explore
On-device assistant ("when does my insurance expire?") using platform models where available
(Android ML Kit GenAI / Gemini Nano on supported devices; Apple Foundation Models on Apple
Intelligence devices), with a graceful fallback to search. Coaching-institute partner packs.

### Non-goals (all versions)
Filling in or submitting portal forms. Any cloud processing of user documents. Accounts that hold more than the email,
name, save count and the link to Pro (2026-10-20: sign-in exists only for the free-save count and Pro across phones;
documents, file names, file hashes and exam choices never go to the account). Web version of the app (the web site is SEO
+ presets only).

## 5. Feature F8 in detail — exam match note
**Where it appears:** (a) the review screen after every exam flow; (b) Custom resize, live as the
user changes KB/px; (c) Checker results; (d) My Kit items ("This signature works for 31 exams").

**What it says:**
- `✓ Accepted by N exams: A · B · C +N-3` → a sheet grouped by body, each row showing why
  (e.g. "20–50 KB ✓ · 200×230 ✓").
- `⚠ K quick fixes`: near misses with the reason and a **Fix** button that re-runs the fit for
  that exam's slot and saves a second file (the original is kept).
- Low-confidence exams: "Likely OK · Unverified" in grey. Never counted in the headline N.
- When N = 0: "Doesn't match any exam in ScanFit — that's fine for other forms" plus the three
  closest exams with their fix buttons.

**Acceptance criteria:**
- Updates within 150 ms of a slider change on a mid-range phone (match only, not encode).
- Uses the *verified written file* (§1.6) for final verdicts, not the in-memory estimate.
- The match note is identical on Android and iOS for every `match_cases` fixture.

## 6. Monetisation (decided with the user 2026-10-20; replaces the ads plan)

**Model: free tier + Pro, no ads.** Every tool, every exam and every edit is free. A free user gets **20 saves in total**
(not per day), then needs Pro to save or share more. Pro is unlimited. There are no banner, interstitial or rewarded ads in
v1.0, so the listing can say "No ads" and the privacy story stays simple.

| | Free | Pro |
|---|---|---|
| All tools, all exams, editing, previews, match note, Checker, My Kit | ✓ | ✓ |
| Saves (files leaving the app) | 20 in total | Unlimited |
| My Kit originals | kept 30 days (user decision 2026-10-15) | kept until deleted |
| Ads, watermark | never | never |

**What counts as a save.** One count per *new verified file* that leaves the app: Save, each file of Save all, Share,
the Checker's "Make it fit" save, and every PDF tool save. Not counted: saving or sharing the same bytes again (a History
re-share, Share after Save of the same review), Save to My Kit (it stays in the app), checking a file, and a save that is
cancelled or fails verification (count only after §1.6 passes).

**When the free saves run out.** The user can still edit, preview, read the match note and check files. Save/Share opens
the paywall, and the draft is retained (30-day drafts), so after buying they save the same file without redoing it. The
count is never a surprise: "N free saves left" shows in Settings, and on the review once 5 or fewer remain. Changing the limit (Remote Config `free_saves_total`, default 20) applies to future saves only; it never takes
back saves a user was already shown.

**Prices.** India ₹99 per year (auto-renewing) and ₹199 lifetime (one-time). Rest of the world $4.99 / $9.99 (store
tier nearest to these). No store free trial: the 20 free saves are the trial, which also avoids the "hidden trial"
complaints seen in competitor reviews (my call; easy to add a 3-day trial on yearly later). Google Play supports UPI,
including UPI Autopay for the yearly plan.

**Paywall rules.** Native screen, both products side by side, the price and period in plain words, "Cancel anytime in
Play Store / App Store settings", Restore purchases, and links to the privacy policy and terms (App Store guideline
3.1.2). Shown only when a save is blocked or from Settings, never on launch. Variants via Remote Config `paywall_variant`.

**Accounts and where the count lives (user decision 2026-10-20).** Sign-in on first launch: Google on both apps, plus
Sign in with Apple on iPhone (Apple requires it next to Google). Firebase Auth; the account's database record holds only
the save count (an integer that can only go up) and timestamps, next to the Auth email and name. The phone keeps a local
copy so saving works offline and syncs later; the "same file again" check stays on the phone (no file hashes leave it).
A reinstall or a new phone with the same account keeps the count; a second Google account starts at 20 again (accepted).
RevenueCat uses the Firebase user id, so Pro follows the account across Android and iPhone; store Restore still works.
No internet at first launch: the app opens anyway, counts locally, and asks for sign-in when online. Settings has Sign out
and **Delete account** (both stores require in-app deletion; deleting removes the Auth user and the record, keeps files on
the phone, and reminds the user that a subscription is cancelled in the store, not by deleting the account). Where the
sign-in gate sits is one setting: if App Review objects to sign-in before any use (Apple guideline 5.1.1(v)), it moves to
the first save.

**Rough economics (illustrative, not a forecast).** After the 15% store fee (Play subscriptions; Apple Small Business
Program, enrol) a ₹99 year nets about ₹84 and a ₹199 lifetime about ₹169, before tax. Example: 20,000 installs in the
first season, 30% use all 20 saves, 5% of those buy at ~₹130 net on average ≈ ₹39,000. Revenue at these prices comes from
volume, so ratings, SEO and seasonal content matter more than price tweaks.

**Watch weekly.** Share of users who reach 20 saves, paywall views → purchases, yearly vs lifetime split, refund rate,
reviews that mention price. Raise or lower the limit only with a season's data (RELEASE_AND_GTM §7).

## 7. Risks
| Risk | Mitigation |
|---|---|
| A wrong preset causes a rejection | Source links, confidence badges, the verify-with-notice step, "Report a problem" (prefilled email, no file attached), preset hotfix via CDN in minutes |
| Portal quietly validates something new | Remote Config flags (`min_fill_strategy`, `allow_grayscale_docs`, thresholds); the upload echo page for testing |
| Store policy (impersonation / government info) | Neutral name and icon, a disclaimer in the listing and first run, official source links shown in the app (Play's government-information disclosure requirement) |
| Seasonality | Cover exam families across the whole calendar; general scanner features in v1.1 for all-year use |
