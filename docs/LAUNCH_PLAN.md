# LAUNCH_PLAN.md — Mr Doc v1.0 on Google Play and the App Store

Written 2026-10-20 from the user's decisions. **Target: production on both stores in the first week of December 2026.**
**Step-by-step instructions for everything marked "You": `docs/OWNER_STEPS.md`.** How releases are built and signed:
`docs/RELEASE_CHECKLIST.md`. Rollout rules, ASO and marketing: `docs/RELEASE_AND_GTM.md`.
Money: PRD §6. Tick items here as they are done; keep dates honest (move them, don't hide them).

## 1. Decisions this plan rests on (user, 2026-10-20)

| Decision | Choice |
|---|---|
| Store name | **Mr Doc**. Code, modules and ids still say `scanfit` until the rename task (§3, week 0) |
| Positioning | An everyday photo and PDF editor, with exam and government forms built in. Private: everything stays on the phone |
| Money | 20 free saves in total, then Pro: ₹99/year or ₹199 lifetime ($4.99 / $9.99 elsewhere). **No ads** |
| v1.0 scope | Lean: what is built + Pro + Settings + legal pages + Hindi review. Scan, guide sheet, live coach → v1.1 |
| SDKs | RevenueCat (purchases) + Firebase (Crashlytics, Analytics, Remote Config, Auth, database, App Check) + Google Sign-In. Nothing else |
| Accounts | **Sign-in on first launch**: Google on both apps, Sign in with Apple on iPhone. The account holds only email, name and the save count (PRD §6). Pro follows the account across phones |
| Accounts | Google Play **personal** account (so the 12-tester, 14-day closed test is mandatory); Apple Developer registered |

## 2. The critical path

```
recruit 12+ testers ─► closed test starts (target Tue 27 Oct) ─► 14 days ─► apply for production access (≈10 Nov)
   ─► Google reviews it (allow up to ~7 days) ─► production staged rollout (from Tue 1 Dec)
Hindi native review ─► clear 338 TODO_HI (final builds refuse them) ─► v1.0.0 tags (by 22 Nov)
payments profile + Paid Apps agreement + bank/tax ─► products can be sold (must be done before Pro testing, week 2)
```

Three things decide the date: **testers opted in by 27 Oct** (every day of delay moves launch by a day), the **Hindi
review**, and the **merchant/agreements setup**. Fallback if Hindi is not reviewed in time: ship v1.0 English-only (turn
the Hindi locale off) and add Hindi in 1.0.1, rather than slip.

The closed test does not need the finished app. It starts with today's build and gets updates during the 14 days;
testers must stay opted in for the whole run and use the app on several days.

## 3. Week by week

Owner: **You** = only you can do it (accounts, money, legal, devices, people). **Claude** = a Claude Code session.

### Week 0 — Tue 20 Oct → Sun 25 Oct: lock things down, start the clock
- [ ] **You** Commit the current work (Home redesign, Kit previews, these docs).
- [ ] **You** Check the name: search "Mr Doc" / "MrDoc" on Play and the App Store, the IP India trademark registry (classes 9
      and 42), and the domain you want for the website. "Doc" can read as "doctor": the store title should say what it
      does, e.g. `Mr Doc: Photo & PDF Resizer` (Play and App Store titles allow 30 characters).
- [x] **You** Pick the permanent ids (they can never change after the first upload). Ideally from a domain you own, e.g.
      `in.mrdoc.app` on both stores. Tell Claude; it changes `applicationId` / `APP_BUNDLE_ID` (RELEASE_CHECKLIST §1).
      _Answered 2026-10-20: `com.roshtech.scanfit` is final on both stores._
- [ ] **You** Play Console: create the app, set up the **payments profile** (merchant account, bank, tax). App Store Connect:
      create the app record, sign the **Paid Apps agreement**, add bank and tax info, enrol in the **App Store Small Business
      Program** (15% commission instead of 30%). These take days to verify, so start now.
- [x] **You** Generate the production presets key and the Android upload keystore (RELEASE_CHECKLIST §1); store both in a
      password manager. _Done (preflight 2026-10-20: prod key, upload key and iOS team are set; CI's release jobs
      sign the presets with the `PRESETS_SIGNING_KEY` secret and upload to TestFlight). Locally only if you build
      releases on your Mac (OWNER_STEPS A5)._
- [ ] **You** Create the Firebase project (Android + iOS apps) and the RevenueCat project.
- [ ] **You** Put the website online: GitHub Pages (user decision 2026-10-20) at `https://imroshan12.github.io/scan-fit/`. Repo Settings › Pages ›
      Source: **GitHub Actions**, then push; `.github/workflows/pages.yml` deploys `web/`. Your details are already
      filled in (individual seller Sarvesh Roshan, Gurugram, `pro.sarvesh.roshan@gmail.com`) and both apps' `AppLinks` point at the site.
- [ ] **You** Sign-in setup: in Firebase Auth turn on Google and Apple; fill in the Google OAuth consent screen (name Mr Doc,
      support email, privacy policy URL); add the **SHA-1 and SHA-256 of both the upload key and the Play App Signing key**
      (Play Console › App integrity) to the Firebase Android app, or Google sign-in fails in Play-installed builds; turn on
      the Sign in with Apple capability for the iOS bundle id. Create the database (Firestore, region `asia-south1`) and
      turn on App Check.
- [ ] **You** Recruit **15+ testers** (aim above 12 because some drop out): friends, family, aspirant WhatsApp/Telegram groups
      (ask moderators first), college groups. Each needs a Google account email. Offer free lifetime Pro to testers who stay
      14 days (store promo codes).
- [ ] **Claude** Rename the app to Mr Doc: display name, the user-facing strings that say "ScanFit" (en + hi), iOS
      `CFBundleDisplayName`, camera usage text. Then the new ids once you have picked them.
      _2026-10-20: everything but the ids is done (also `Downloads/Mr Doc/<Exam>/` on Android). Waiting on your ids._
- [x] **Claude** Free saves + paywall (over the stand-in billing) and the full Settings screen, both apps (2026-10-20,
      pulled forward from week 1; HANDOFF §4).
- [ ] **You** Device test on your phone (HANDOFF §5, top items) and report anything broken. Claude fixes P0s.
- [ ] **You + Claude** First release candidate: tag `android-v0.9.0-rc1` and `ios-v0.9.0-rc1`; upload the AAB by hand to
      Play internal testing (Google opens the publishing API only after the first manual upload); the iOS tag goes to
      TestFlight.

### Week 1 — Mon 26 Oct → Sun 1 Nov: closed test running, Pro being built
- [ ] **You** **Start the Play closed test by Tue 27 Oct** with every tester opted in. Write the date down: 14 days later is Tue 10 Nov.
- [ ] **You** TestFlight external group (needs a short Beta App Review, usually about a day) with the same testers on iPhone.
- [ ] **Claude** Accounts: welcome + sign-in screen (Google; Apple on iPhone), offline first launch, `AccountRepository`,
      Sign out and Delete account in Settings, database security rules (own record only, count only goes up), App Check.
- [ ] **Claude** Pro + free saves (PRD §6, ARCHITECTURE §8): spec the counting rules (ALGORITHMS), `SaveQuota` synced to the
      account, RevenueCat `logIn(uid)`, paywall, Restore, offline entitlement, both apps, VM tests, previews.
      _Started early 2026-10-20: ALGORITHMS §14, local `SaveQuota` + gate, paywall and Restore on both apps over a
      stand-in `Billing`. Open: RevenueCat (needs your project + keys), account sync, offline entitlement._
      _QA follow-up: delayed-store lifecycle fixed/tested on both apps; 4 native iOS simulator smoke tests pass,
      including fake purchase, Restore, unavailable store and Hindi largest text. Not a real-store purchase check._
- [ ] **Claude** Firebase: Crashlytics (no paths in keys), Analytics from `spec/analytics/events.json` (drop
      `rewarded_completed`, add `free_saves_spent`), Remote Config defaults (`free_saves_total` = 20, `paywall_variant`),
      advertising-ID collection off, opt-out in Settings.
- [x] **Claude** Settings (language, presets version + update, unverified toggle, privacy, help, Report a problem,
      disclaimer, Pro status, free saves left, Restore, analytics opt-out).
      _Done early 2026-10-20 (no presets update button until OTA sync; links are placeholders until the domain is
      picked; Account rows come with sign-in)._
- [x] **Claude** Website: landing page, privacy policy, terms, and an **account deletion page** (Play requires a web link for
      deletion requests). The privacy policy names Firebase (Auth: email, name; database: save count; Crashlytics,
      Analytics) and RevenueCat, and what each receives. The privacy policy URL is required by both stores.
      _2026-10-20: `web/` index, privacy, terms, help, delete-account (+ `site.css`, icon). Placeholders and `REVIEW:`
      comments for you and a lawyer; preflight warns until they are gone (blocks `--final`)._
- [ ] **You** Have a lawyer review `web/privacy/` and `web/terms/` (search for `REVIEW:`), especially DPDP and under-18s.
- [ ] **You** Find a native Hindi reviewer and send them `spec/strings/hi.json` (338 `TODO_HI` strings).

### Week 2 — Mon 2 Nov → Sun 8 Nov: Pro in testers' hands, store setup
- [ ] **You** Create the products: Play `pro_yearly` (subscription, base plan ₹99/year) and `pro_lifetime` (one-time ₹199);
      App Store an auto-renewable subscription group with `pro_yearly` and a non-consumable `pro_lifetime`. Same ids in
      RevenueCat (entitlement `pro`). Add yourself and testers as Play license testers; make a StoreKit sandbox tester.
- [ ] **You + Claude** `-rc2` with sign-in, Pro, Settings and Firebase to both test groups. Test sign-in on a
      **Play-installed** build (not just a debug build), a real UPI purchase, a refund, the 21st save, reinstall → the
      count is kept, the same account on a second phone (count and Pro follow), Delete account, and a first launch offline.
- [ ] **You** Store listings in English and Hindi: the text, Data safety answers and form answers are drafted in
      `docs/STORE_LISTING.md` (2026-10-20); you fill the placeholders, get the Hindi reviewed, and make the 8
      screenshots, feature graphic and 512 px icon listed there. Note: the **closed test needs a listing and Data safety
      too**, so paste the text and STORE_LISTING §5A ("no data collected" for builds without Firebase) in week 0–1.
- [ ] **Claude** Device matrix and accessibility pass on what testers report (TESTING §4); fix every P0/P1.

### Week 3 — Mon 9 Nov → Sun 15 Nov: production access, forms, polish
- [ ] **You** On or after **Tue 10 Nov** (14 full days), apply for production access in Play Console and answer the questions
      about the test honestly (what testers found, what you fixed). Review can take up to about 7 days.
- [ ] **You** Fill in Play **Data safety** (email address and name for the account; user IDs; purchase history via
      RevenueCat; crash logs, diagnostics, app interactions and app-instance IDs via Firebase; no photos/files collected,
      everything processed on device; account deletion URL), **App access** (reviewers sign in with any Google account), **content rating** (IARC),
      **target audience** (16–17 and 18+; no ads, so the teen rules are easy), the **government information** declaration
      plus the disclaimer, and the ads declaration (no ads). App Store: **App Privacy** labels to match (contact info linked to
      the user, for app functionality), and check that `PrivacyInfo.xcprivacy` lists the RevenueCat, Firebase and
      GoogleSignIn SDKs.
- [ ] **Claude** Performance on the low-end profiles (ARCHITECTURE §11) and fixes from tester feedback.
- [ ] **You** Get the Hindi review back. **Claude** clears the `TODO_HI` prefixes.

### Week 4 — Mon 16 Nov → Sun 22 Nov: final builds and submission
- [ ] **Claude** Final presets (signed with the production key), version 1.0.0 on both apps, `release_preflight.py --final`
      clean, all gates green.
- [ ] **You** Tag `android-v1.0.0` and `ios-v1.0.0`. Submit iOS for App Review (usually 1–3 days). Review notes: documents are
      processed on the phone; the account (Google or Apple) exists to keep the free-save count and Pro across devices;
      Delete account is in Settings; not affiliated with any government body; how to reach the paywall (save 21 files, or
      Settings › Pro).
- [ ] **You** When production access is granted: create the production release in Play with the 1.0.0 AAB, but don't roll it
      out yet. Turn on pre-registration if there is time.

### Week 5 — Mon 23 Nov → Sun 29 Nov: buffer
- [ ] Fix App Review rejections, if any. Website live with the store links. Prepare launch content: 6 short videos (Hindi +
      English) from RELEASE_AND_GTM §4, posts for the tester groups, a LinkedIn founder post.

### Launch — Tue 1 Dec → Mon 7 Dec
- [ ] **Android** staged rollout: 10% (Tue 1) → 25% (Thu 3) → 50% (Sat 5) → 100% (Mon 7), each step only if crash-free ≥ 99.5%, ANR
      < 0.3% and no credible "portal rejected it" cluster on one exam (RELEASE_AND_GTM §2).
- [ ] **iOS** release on approval with phased release (7 days), pausable the same way.
- [ ] **You** Reply to every review daily for the first 4 weeks. Watch Play Console vitals, Crashlytics and RevenueCat daily.
- [ ] Post the launch content; ask testers for honest reviews (never ask for 5 stars or offer anything for a review).

## 4. After launch

- **Weeks 1–4:** fix crashes and any wrong preset within 24 h (RELEASE_AND_GTM §2 incident steps). Weekly KPIs (§6 of
  RELEASE_AND_GTM, plus the PRD §6 money metrics).
- **v1.1 (Dec → Jan):** Scan entry, guide sheet, live coach (moved out of v1.0), then the PRD v1.1 scanner features. Aim to
  ship before the NEET UG / CUET UG registration wave (Jan–Mar 2027; check the actual dates).
- **Season content:** an exam page and a short video the day a big notification drops.

## 5. Risks

| Risk | What we do |
|---|---|
| Fewer than 12 testers stay opted in for 14 days | Recruit 15+, remind them on days 3, 7 and 12, give them something to try each week |
| Production access refused after the test | Google says why; fix, run the test again (another 14 days). Treat tester feedback seriously so the application is strong |
| "Mr Doc" clashes with an existing app or trademark | Checked in week 0, before the listing, website and ids are made |
| Apple rejects sign-in before any use (guideline 5.1.1(v)) | The gate's position is one setting: move it to the first save and resubmit (a day, not a rewrite) |
| Google sign-in works in debug but fails from Play | Both SHA fingerprints (upload key and Play App Signing key) in Firebase in week 0; test a Play-installed build in week 2 |
| No internet at first launch | The app opens anyway, counts locally and asks for sign-in when online |
| App Review asks about government content or the paywall | Disclaimer in the app and listing, official source links in the app, paywall shows price, period, Restore, privacy and terms |
| India's Digital Personal Data Protection Act (DPDP) | Sign-in now collects email and name, Firebase processes device identifiers, and many users are under 18 (DPDP treats under-18s as children and expects verifiable parental consent). Keep the account to email + count, analytics to the enum set, ad ID off, an opt-out in Settings, and a clear privacy policy. **Get a lawyer's view** on consent, children's data and when the DPDP Rules' duties apply, before launch |
| Hindi review late | Ship English-only v1.0 and add Hindi in 1.0.1 (§2) |
| Low revenue at ₹99 / ₹199 | Expected: money comes from volume (PRD §6). Tune `free_saves_total` and paywall variants only with a season's data |
