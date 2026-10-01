# RELEASE_AND_GTM.md — rollout, marketing, beating the competition

## 1. Competitive landscape (researched Sept 2026)
| Type | Examples | Strengths | Gaps we exploit |
|---|---|---|---|
| Exam-resizer **websites** | sarkaridoc, ExamMint (121 exam presets), DocSet, PhotoKB, SizeToKB, ConvertImage, many more | Free, SEO-dominant, many already process in the browser | Browser-only: no guided capture from paper, weak signature/thumb cleanup, no live-photo practice, no reuse across exams, **they contradict each other** on specs, no verification of the saved file, poor on low-end phones |
| Play Store resizer apps | many "Photo & Signature Resizer" apps | Installed base | Ad-heavy, generic, rarely updated specs, watermarks |
| General scanners | Adobe Scan (free OCR capped at 5 pages; compression is premium), CamScanner (ads, past malicious-SDK incident), OneDrive (replaced Microsoft Lens, saves to cloud only) | Brand, scanning quality | Not exam-aware; can't hit "20–50 KB at 200×230" |

**Our wedge, in one sentence:** the only app that captures from paper, cleans, fits, *verifies*
and tells you exactly which exams accept the file, with specs you can trace to a source.

Value we add that competitors don't (the list for the store listing and videos):
1. **Exam match note** on every file: "Accepted by 14 exams", with one-tap fixes.
2. **Guided paper capture** + ink cleanup for signature, thumb and declaration (the hardest uploads).
3. **Minimum-size handling**: we hit portal *minimums* too, which most resizers get wrong.
4. **Verify-after-save + Checker**: catches CMYK, progressive, HEIC, wrong extension, EXIF rotation.
5. **My Kit**: capture once, and every new exam takes seconds.
6. **Live-photo coach** for SSC/UPSC/NTA live capture.
7. **Traceable specs**: source link + verified date + confidence badge on every exam; updated OTA.
8. **Offline, private, no watermark, Hindi.**

## 2. Release process
**Tracks.** Android: internal (team, every merge) → closed (testers, 14-day rule if personal
account) → production staged rollout. iOS: TestFlight internal → external → App Review →
phased release.

**Pre-release checklist** (becomes `docs/release-checklist.md`):
- [ ] Version bumped; changelog EN/HI; presets `VERSION` and the embedded snapshot current
- [ ] Release gates in TESTING §7 green
- [ ] Remote Config defaults reviewed; kill switches tested
- [ ] Play: target API 36, 16 KB page-size check passed in the APK Analyzer, Data safety matches the SDKs, content rating done, government-information disclosure in the description ("Not affiliated with any government body; specs sourced from official notifications, linked in-app")
- [ ] iOS: privacy manifest + App Privacy labels match the SDKs; screenshots for required device sizes; review notes explain offline processing and the non-affiliation
- [ ] Store assets contain **no** government emblems, exam-body logos, or wording like "Official"
- [ ] Crash/ANR dashboards and alerts set (Crashlytics velocity alerts)

**Staged rollout.** 10% → 25% (48 h) → 50% (48 h) → 100%. Halt on crash-free < 99.5%, ANR > 0.3%,
or ≥ 3 credible "rejected by portal" reports on one exam. iOS phased release can be paused the same way.

**Incident: wrong preset.** (1) Hotfix the preset JSON, bump VERSION, publish (minutes). (2) Raise
`min_presets_version` in Remote Config so the app syncs before opening flows. (3) Add a banner on
that exam: "Spec corrected on <date>. Re-make your files". (4) Post-mortem in `docs/portal-log.md`.

## 3. ASO (App Store Optimisation)
- **Name/title:** brand + generic keywords, no exam-body names in the title (impersonation risk).
  E.g. "ScanFit: Exam Photo & Sign Resizer". Short description: "Photo, signature, thumb &
  PDF fixed to exact KB for 55+ exams. Offline."
- **Long description:** exam names appear naturally in lists ("Supports presets for IBPS, SBI,
  SSC, UPSC, RRB, NTA exams like JEE Main, NEET and CUET…") + disclaimer + source statement. A
  Hindi localised listing (Play supports hi-IN) ranks for Hindi queries.
- **Screenshots (8):** 1 "Accepted by 14 exams" moment, 2 signature paper → clean, 3 exam
  checklist, 4 Checker catching an error, 5 My Kit, 6 live coach, 7 PDF ID card, 8 Hindi UI.
- **Ratings:** in-app review prompt (Play In-App Review / `requestReview`) only after a verified
  save **and** a "Portal accepted it" yes. Never after an error. Reply to every review in week 1–4.
- Seasonal Custom Store Listings (Play) / Custom Product Pages (iOS): an "NEET/JEE" variant in
  Jan–Mar, a "Bank exams" variant in Jul–Sep.

## 4. Marketing plan (budget-light, founder-led)
**Pre-launch (Phase 3–5):**
- **Programmatic SEO site** from the same presets: `/exam/<id>` pages ("IBPS PO photo &
  signature size 2026 — exact KB, pixels, sources") with a "Make these files in the app" deep
  link, a JSON-LD FAQ, Hindi versions, and a "last verified" date. This is exactly how the
  web competitors win traffic, and our data is better (sources + confidence). Generated at build
  from `spec/presets`, so it's never stale.
- Play **pre-registration** + an iOS pre-order page. Waitlist on the site.
- Recruit closed-test testers from aspirant communities (Telegram/Reddit r/SSC, r/bankexams-type
  groups, college groups). Ask moderators before posting and offer free lifetime Pro to testers.

**Launch:**
- Short vertical videos (YouTube Shorts, Instagram Reels) in Hindi + English: "Form rejected
  because of signature? Fixed in 10 seconds", "Your 19 KB photo gets rejected — here's why",
  "Which exams accept this photo?" Reuse the Reels workflow; 3/week for 4 weeks.
- A founder post on LinkedIn about building it (the dev audience shares it, and it helps your profile too).
- Product Hunt / Indie Hackers is optional (low fit for the audience; good for the iOS global audience).

**Growth loops:**
- **Share checklist:** "My IBPS PO files are ready ✓", an image card with an app link (no personal data).
- **Referral:** give a friend 7 days of Pro, get 7 days (Remote Config gated).
- **Cyber cafés / CSC operators** (they fill forms for many candidates): a "Operator" tip in the
  listing plus a simple Pro value. Later: batch mode (v1.1).
- **Coaching institutes:** a free branded "upload kit guide" PDF + promo codes (v2 partner packs).
- **Notification-driven spikes:** when a big exam notification drops, same day: preset verified
  → exam page updated → a Short about that exam's upload rules → a push to "My exams" users (v1.2).

**Paid (optional, after organic proof):** Google App Campaigns for installs targeted at exam
weeks, capped ₹500–1,000/day, and only if D7 retention ≥ 15% and payer ARPU supports it.

## 5. Exam-season calendar (plan content and verification around it)
Rolling: IBPS (PO/Clerk/RRB/SO) and SBI notifications mid-year to autumn; SSC exams through the
year; NTA JEE Main (session 1 registration around Oct–Nov), CUET UG and NEET UG (Jan–Mar);
GATE (Aug–Oct); CAT (Aug–Sep); UPSC CSE (Jan–Feb). **Confirm exact dates from each notice**. They
move every year. Keep `docs/exam-calendar.md` updated weekly.

## 6. KPIs and review cadence
Weekly: installs by source, activation (first verified save), exports/user, match-note views →
fix taps, paywall conversion, rewarded completions, crash/ANR, preset sync success, portal-accepted rate.
Monthly: D7/D30 by install cohort (aligned to exam seasons), revenue split (subs / lifetime /
ads), rating trend, top support issues → roadmap.

## 7. Pricing experiments (Remote Config `paywall_variant`)
A: ₹99/yr + ₹199 lifetime (control) · B: ₹149/yr + ₹249 lifetime · C: ₹49 per exam season (90
days) + ₹199 lifetime. Run each for ≥ 2 weeks inside a comparable exam season. Measure revenue
per paywall view, not just conversion.
