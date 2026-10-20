# OWNER_STEPS.md — everything you (the owner) do to launch Mr Doc, step by step

Written 2026-10-20. The schedule is `docs/LAUNCH_PLAN.md`; this file is the **how**. Work top to bottom: each part
matches a LAUNCH_PLAN week. Steps marked ⏳ involve someone else's review or verification (days), so start them early.
Steps marked 🤖 are where you hand something to Claude.

Store consoles change their menus often. If a menu name below differs slightly, search the console for the words in
bold. Never paste passwords, private keys, the presets seed, keystore files or service-account JSON into a chat with
Claude or into the repo: they go into the consoles, your password manager, or the git-ignored files named below.

---

## 0. Decisions only you can make (answer these first)

| # | Decision | Why it matters | Default if you don't care |
|---|---|---|---|
| Q1 | **App id** for both stores | Permanent after the first upload, never visible to users | **Answered: `com.roshtech.scanfit`, final** |
| Q2 | **Who is the seller** | Shown on the store pages, the privacy policy and the terms | **Answered: you, as an individual (Sarvesh Roshan)**. Set the Play developer name to "Sarvesh Roshan" too (the delete-account page names it); Apple shows your legal name |
| Q3 | **Support email** | Both stores, the website, "Report a problem" | **Answered: `pro.sarvesh.roshan@gmail.com`** |
| Q4 | **Website address** | Privacy policy and account deletion URLs in both stores | **Answered: GitHub Pages, `https://imroshan12.github.io/scan-fit/`** (the repo is public, so it's free; a custom domain can come later) |
| Q5 | **City** for the terms' "courts in…" clause | Legal text | **Answered: Gurugram (Gurgaon), Haryana** |

Done 2026-10-20: every placeholder in `web/` and the app's links (`AppLinks`) now use these answers. Only the lawyer's
`REVIEW:` notes remain in the policy pages.

---

## Part A — Week 0, now → Sun 25 Oct: lock things down, upload the first test build

### A1. Commit what is on disk
Everything since `1209812` is uncommitted: the rename, free saves, paywall, Settings, the website and the launch docs.
```bash
git add -A
git commit -m "Mr Doc rename, free saves, paywall, Settings, website, launch docs"
git push
```
`.claude/launch.json` (a local website preview server) is fine to commit. Done when `git status` is clean.

### A2. Check the name "Mr Doc" (30 minutes)
1. Search **Play Store** and **App Store** for "Mr Doc", "MrDoc", "Mr. Doc". A different kind of app with the same name is
   usually fine; a document or scanner app with the same name is a problem.
2. Search the Indian trademark registry: **IP India public search** (ipindiaservices.gov.in › Trade Marks › Public
   Search), word mark "MR DOC", classes **9** (software) and **42** (online services). Note any live marks.
3. The App Store name must be unique across the store. If `Mr Doc: Photo & PDF Resizer` is taken when you create the app
   (A8), try `Mr Doc – Photo & PDF Resizer` or `Mr Doc: PDF & Photo Resize`.
4. Check domains if you want one: `mrdoc.in`, `mrdoc.app`, `getmrdoc.com`.

If you find a clash, stop and tell Claude before A7/A8: the name goes into the store records.

### A3. Support email
Answered: `pro.sarvesh.roshan@gmail.com`. Users will write there (Report a problem, account deletion, refunds), so check it daily after launch.
Optionally add a Gmail filter that labels mail with "Mr Doc" in the subject.

### A4. Put the website online with GitHub Pages (5 minutes)
The workflow `.github/workflows/pages.yml` publishes `web/` on every push to `main` that changes `web/`.
1. On github.com open `imroshan12/scan-fit` › **Settings › Pages**. Under **Build and deployment › Source** choose
   **GitHub Actions**. (Nothing else to set.)
2. Push (A1). Open the **Actions** tab: the **pages** run should go green in about a minute. If it didn't start, open
   it and click **Run workflow**.
3. Open `https://imroshan12.github.io/scan-fit/` and check `/privacy/`, `/terms/`, `/help/`, `/delete-account/` and `/upload-test/`.
4. Good to know: your repository is **public**, so anyone can read the app's code (no keys or passwords are in it;
   they're all git-ignored). GitHub Pages is free for public repos; a private repo would need a paid GitHub plan or a
   move to Cloudflare Pages. GitHub Pages ignores `web/_headers` (only small security headers are lost).
5. Later, with a domain: Settings › Pages › **Custom domain**, then tell Claude so the app links follow.

### A5. Sign the exam presets with your production key (5 minutes)
This is the only blocker the release check reports. In a terminal at the repo root:
```bash
source .venv/bin/activate
PRESETS_SIGNING_KEY='<your presets seed from the password manager>' spec/tools/build_all.sh --release --allow-todo-hi
python3 spec/tools/release_preflight.py
```
Done when the preflight shows **no BLOCKER** (warnings about Hindi, links and billing are expected for test builds).
Re-run it after every change to `spec/presets/`.

### A6. Google Play Console: account and app ⏳
1. Open **play.google.com/console**. If the dashboard asks you to **verify your identity**, a **contact phone/email**, or to
   **verify access to an Android device** (Play Console app on an Android phone), finish those first: they gate everything.
2. **Create app**: app name `Mr Doc: Photo & PDF Resizer`; default language **English (United States)** (add Hindi later
   as a translation); **App**; **Free** (free apps can still sell Pro); accept the declarations.
3. **Payments profile** ⏳: **Settings › Payments profile** (or **Monetize › Monetization setup**), create the merchant
   profile, add your bank account (a small test deposit may need confirming) and the tax details the form asks for (for an
   individual in India this is usually your PAN; GSTIN if registered). Ask your CA about GST/income tax on payouts.
4. **App signing**: keep **Play App Signing** on (default). After your first upload, **Test and release › Setup › App
   signing** (or **App integrity**) shows the **SHA-1 and SHA-256 of the app signing key**: you need them in B1.

### A7. Apple: identifiers, agreements, app record ⏳
1. **developer.apple.com › Certificates, Identifiers & Profiles › Identifiers**: create (or open) the App ID
   `com.roshtech.scanfit` and tick **Sign in with Apple** (In-App Purchase is on by default). Save.
2. **appstoreconnect.apple.com › Business** (or **Agreements, Tax, and Banking**): accept the **Paid Apps** agreement
   (the Account Holder must do it), add a **bank account** and the **tax forms** (an individual in India fills the US form
   W-8BEN; ask your CA about the India–US treaty). Wait until the agreement says **Active**: without it you can't sell Pro.
3. **Apple Small Business Program**: developer.apple.com › Programs › App Store Small Business Program › enrol. It cuts
   Apple's commission from 30% to 15%.
4. **Apps › + › New App**: iOS; name `Mr Doc: Photo & PDF Resizer`; primary language English (U.S.); bundle id
   `com.roshtech.scanfit`; SKU e.g. `MRDOC-IOS-1`; full access.
5. **App Store Connect API key** (used to upload builds from the terminal or CI): **Users and Access › Integrations › App
   Store Connect API › +**, role **App Manager**. Download the `.p8` (only possible once) into your password manager or
   a folder outside the repo; note the **Key ID** and **Issuer ID**.

### A8. Recruit testers (start today, keep going until 15+)
Google requires **at least 12 testers opted in for 14 days in a row** before you can apply for production. Aim for 15+.
1. Ask friends, family, college groups, and aspirant WhatsApp/Telegram groups (ask admins first).
2. Collect each person's **Gmail address** (Android) and, for iPhone users, their **Apple ID email** (TestFlight).
3. Make a WhatsApp group for feedback. Message to send:
   > I'm launching Mr Doc, an app that resizes photos, signatures and PDFs for exam and government forms. Can you test
   > it for 2 weeks? Open the link I send, tap "Become a tester", install it from Play Store, keep it installed for 14
   > days, use it on a few different days, and tell me what's confusing or broken. Testers who stay all 14 days get Pro
   > free for life.
4. Keep a sheet: name, email, platform, date opted in. You need it for the production application (step D1).

### A9. Test on your own phone (1 hour)
Install a debug build (Android Studio › Run, or Xcode › Run on your iPhone) and try, then tell Claude what happened with
**platform, OS version and where the photo came from (camera / gallery / Files)**:
1. Exam forms › IBPS PO › Photo from the **camera** → Save → Done. Does Done go back to the exam with "Saved"? (open issue)
2. Signature from a photo of paper → Save → open the saved file. Is every stroke there?
3. Thumb impression from a real inked thumb.
4. Compress PDF on a real certificate PDF (Android especially: the real PDF reader has never run there).
5. Home cards and My Kit previews (History file preview, thumbnails).
6. Upload a saved file to `https://<your site>/upload-test/` in the phone browser: the size and pixels must match.

### A10. Ask Claude to make test builds generous 🤖
Release test builds use the stand-in billing, which can't sell Pro, so a tester who uses all 20 free saves is stuck. Ask
Claude: "Make -rc builds allow 200 free saves until RevenueCat is in." (A small change; final builds keep 20.)

### A11. Store listing and graphics (needed before the closed test)
The closed test needs a complete store listing.
1. Text: copy from `docs/STORE_LISTING.md` §2 (English). Hindi (§3) can wait for the review.
2. Graphics: 512×512 icon, 1024×500 feature graphic, at least 2 phone screenshots (9:16). 🤖 Claude can make the icon and
   feature graphic and the iPhone screenshots from the simulator; Android screenshots need your phone (screenshot the
   screens listed in STORE_LISTING §7, with sample documents only, never real IDs or signatures).

### A12. First test build (Fri 23 – Sat 24 Oct)
Android, signed with your upload key (`android/keystore.properties` is already set up):
```bash
cd android
./gradlew :app:bundleRelease -Pscanfit.versionName=0.9.0 -Pscanfit.versionCode=1
# the file: android/app/build/outputs/bundle/release/app-release.aab
```
Every later upload needs a higher `versionCode` (2, 3, …).

iOS (your team is in `ios/Config/Local.xcconfig`; Xcode must be signed in to that Apple account):
```bash
cd ios
ASC_KEY_PATH=/path/outside/repo/AuthKey_XXXX.p8 ASC_KEY_ID=XXXX ASC_ISSUER_ID=xxxxxxxx-xxxx \
  Scripts/archive.sh --version 0.9.0 --build 1 --upload
```

### A13. Play: first upload and the "Set up your app" tasks
1. **Test and release › Testing › Internal testing › Create new release**: upload `app-release.aab`, release name
   `0.9.0 (1)`, notes "First test build". Save › Review › **Start rollout**. Add yourself as an internal tester.
2. Open the **Dashboard** and finish every **Set up your app** task (answers in `docs/STORE_LISTING.md`):
   - **Privacy policy**: `https://<your site>/privacy/`
   - **App access**: "All functionality is available without special access" (true until sign-in arrives)
   - **Ads**: No
   - **Content rating**: the questionnaire (STORE_LISTING §6)
   - **Target audience**: 16–17 and 18 and over
   - **Data safety**: **STORE_LISTING §5A** ("no data collected") for this build. You will change it in D3.
   - **Government apps**: not a government app; **Financial features**, **Health**, **News**: none
   - **App category**: Productivity; contact email, website
   - **Main store listing**: text + graphics from A11

### A14. Start the closed test ⏳ — target Tue 27 Oct
1. **Test and release › Testing › Closed testing** › open the default track (often "Alpha") › **Testers**: create an
   email list with your testers' Gmail addresses › save.
2. **Countries/regions**: India (add others if you have testers there).
3. **Create release** › **Add from library** › pick the 0.9.0 (1) build › notes › Review › **Start rollout to Closed
   testing**. Google reviews it (hours to a few days), which is why this starts by the weekend.
4. When approved, copy the **opt-in link** from the Testers tab and send it to the testers with the A8 message.
5. Watch the dashboard: it counts opted-in testers and the 14 days. **Write down the date 12+ are in** (day 14 = the day
   you can apply).

---

## Part B — Week 1, Mon 26 Oct → Sun 1 Nov: services for sign-in and Pro

### B1. Firebase ⏳ (1–2 hours)
1. **console.firebase.google.com › Add project** `Mr Doc`. Enable Google Analytics (create an Analytics account; in its
   data-sharing settings, turn off sharing with Google products and benchmarking).
2. **Add app › Android**: package `com.roshtech.scanfit`, nickname "Mr Doc Android". Add **SHA-1 and SHA-256** for three
   keys (Project settings › your Android app › Add fingerprint):
   ```bash
   # debug key (for Claude's and your debug builds)
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   # upload key (path and alias are in android/keystore.properties; it asks for the store password)
   keytool -list -v -keystore /path/to/your-upload.jks -alias <your alias>
   ```
   plus the **app signing key** fingerprints from Play Console (A6 step 4). Without the Play one, Google sign-in fails in
   builds installed from Play even though it works in testing.
3. Download **`google-services.json`** into `android/app/` (git-ignored).
4. **Add app › iOS**: bundle id `com.roshtech.scanfit`. Download **`GoogleService-Info.plist`** into `ios/ScanFit/`
   (git-ignored).
5. **Build › Authentication › Get started › Sign-in method**: enable **Google** (set the support email) and **Apple**
   (for iPhone sign-in only the App ID capability from A7 is needed; leave the Services ID fields empty).
6. **Google Cloud console** (same project) › **APIs & Services › OAuth consent screen / Branding**: app name `Mr Doc`,
   support email, app home page, privacy policy and terms URLs, authorised domain (your site's domain). Audience:
   **External**, publishing status **In production** (only email and profile are requested, so no Google verification).
7. **Build › Firestore Database › Create database**: **production mode**, location **asia-south1 (Mumbai)**. The location
   can never change. Claude's session writes the security rules.
8. **App Check**: register the Android app with **Play Integrity** (Play Console › App integrity › link the same Google
   Cloud project) and the iOS app with **App Attest**. Leave enforcement **off** until Claude says the app sends tokens.
9. **Crashlytics**: open it once and click through "Enable".
10. The free **Spark** plan is enough to start.

🤖 Tell Claude "Firebase config files are in place" (never paste their contents).

### B2. RevenueCat ⏳ (1 hour + up to 36 hours for Google credentials)
1. Sign up at **app.revenuecat.com**; create project `Mr Doc`.
2. **Google Play app**: package `com.roshtech.scanfit`. It needs a **service account JSON**:
   - Google Cloud console (same project) › **APIs & Services › Library**: enable **Google Play Android Developer API**
     (and **Google Play Developer Reporting API** if RevenueCat asks).
   - **IAM & Admin › Service accounts › Create**: name `revenuecat`. **Keys › Add key › JSON**: download.
   - Play Console › **Users and permissions › Invite new users**: the service account's email; app permissions **View app
     information**, **View financial data**, **Manage orders and subscriptions**. Invite.
   - Upload the JSON in RevenueCat's Play app settings. Delete your local copy afterwards (or keep it only in the
     password manager). RevenueCat may show "credentials invalid" for up to 36 hours: that's normal.
3. **App Store app**: bundle id `com.roshtech.scanfit`. Create an **In-App Purchase key** in App Store Connect › Users and
   Access › Integrations › **In-App Purchase** › +, download the `.p8`, upload it to RevenueCat with its Key ID and your
   Issuer ID.
4. **Entitlements**: create `pro`.
5. Products and the offering come after C1 (the store products must exist first).
6. Copy the two **public SDK keys** (Project settings › API keys: one starts `goog_`, one `appl_`). These are meant to be
   in apps, so you can send them to Claude 🤖.

### B3. Start Claude's sign-in + RevenueCat + Firebase session 🤖
Click the **"Sign-in + RevenueCat + Firebase (both apps)"** card (or ask for it). It builds everything with stand-ins and
switches to the real services when B1/B2 files and keys are present.

### B4. TestFlight
1. After A12's upload finishes processing: App Store Connect › your app › **TestFlight**. Answer **export compliance**
   if asked (the app declares no non-exempt encryption).
2. **Internal testing**: add yourself; install with the TestFlight app.
3. **External testing › +** group "Testers": add the iPhone testers' emails. Fill **Test Information** (what to test,
   feedback email; "no sign-in needed" for now) and submit the build for **Beta App Review** ⏳ (about a day).

### B5. Hindi review (send it this week) ⏳
Send your reviewer `spec/strings/hi.json` (about 370 strings start with `TODO_HI:`) and `docs/STORE_LISTING.md` §3.
Instructions for them:
- Fix the Hindi after `TODO_HI:` and delete the `TODO_HI:` prefix when a string is right.
- Don't change anything in `{…}` (for example `{number:int}`) or the keys on the left.
- Keep it short and simple; plain words over formal Hindi.

🤖 When it comes back, give the file to Claude, which checks and regenerates the app strings.

### B6. Lawyer ⏳
Send `web/privacy/index.html` and `web/terms/index.html` (or the live pages) and ask:
1. Does the policy meet India's DPDP Act 2023 and its Rules for the launch date (notice, consent, contact person)?
2. Users aged 16–17 will sign in (email, name) and send usage statistics: what does verifiable parental consent mean
   for us, and what should the app do?
3. Data stored outside India (Firebase, RevenueCat): anything to add?
4. Are the terms' liability, refund and "lifetime" clauses OK under Indian consumer law?
5. Anything else in the `REVIEW:` comments in the page source.

🤖 Send Claude the answers; it edits the pages and the app if needed.

### B7. Tester care
Day 3 after opt-in: a short message in the group ("try making a signature and compressing a PDF this week"). Note every
problem they report in your sheet.

---

## Part C — Week 2, Mon 2 Nov → Sun 8 Nov: Pro in testers' hands

### C1. Create the store products
Play only lets you create products after a build **with the billing library** is uploaded, so first upload Claude's
RevenueCat build (rc2) to internal testing (A12 + A13 step 1, `versionCode` 2 or higher).
- **Play**: **Monetize › Products › Subscriptions › Create subscription**: product id `pro_yearly`, name "Mr Doc Pro
  (yearly)" › **Add base plan**: id `yearly`, **auto-renewing**, billing period **1 year**, price **₹99** (then let Play
  convert other countries, or set about $4.99) › Activate. **One-time products › Create**: id `pro_lifetime`, name "Mr
  Doc Pro (lifetime)", price **₹199** (about $9.99) › Activate.
- **App Store**: your app › **Monetization › Subscriptions** › create group "Mr Doc Pro" › **+** `pro_yearly`, duration
  **1 year**, price ₹99 tier, display name and description (English + Hindi), and a **review screenshot** of the paywall.
  **In-App Purchases › +** **Non-Consumable** `pro_lifetime`, ₹199, same details. They are reviewed together with the
  app version in E3.
- **RevenueCat**: **Products** › import both stores' products › attach them to the `pro` entitlement › **Offerings**:
  `default` with packages **Annual** → `pro_yearly` and **Lifetime** → `pro_lifetime`. Make `default` current.

### C2. Test buyers
- Play: **Settings › License testing**: add your and your testers' Gmail addresses (their purchases are test purchases,
  never charged).
- Apple: **Users and Access › Sandbox › Test accounts**: create one with a new email; sign in to it on the iPhone under
  Settings › App Store › Sandbox Account.

### C3. Test rc2 yourself (the list from LAUNCH_PLAN week 2)
On a build **installed from Play** (internal or closed track) and from TestFlight: sign in; reinstall (count kept);
same account on a second phone (count and Pro follow); the 21st save opens the paywall; buy yearly and lifetime with test
payments; Restore; Delete account; first launch with the phone in airplane mode. Report anything odd to Claude with the
platform and OS.

### C4. Day 7 tester check-in
Ask what they used and what confused them. Write a 5-line summary in your sheet: you'll paste it into the production
application.

---

## Part D — Week 3, Mon 9 Nov → Sun 15 Nov: production access and the privacy forms

### D1. Apply for production access ⏳ — on or after the 14th day (target Tue 10 Nov)
Play Console **Dashboard › Apply for production**. You answer three groups of questions; be specific and honest:
1. **About your closed test**: how you recruited testers (friends, aspirant groups), how many, how engaged they were,
   what feedback they gave (your C4 summary) and what you changed because of it.
2. **About your app**: who it's for (people filling exam and government forms in India), what it does, why it's
   different (private, offline, exact sizes).
3. **Production readiness**: what you fixed, how you know it's ready (crash-free test, device checks).
Google replies within about 7 days. If refused, the email says why; fix it and run the closed test again.

### D2. Release review notes ready
Keep the Apple review notes from LAUNCH_PLAN week 4 in a text file; you'll paste them in E3.

### D3. Update the privacy forms for the v1.0 build
Before any build **with sign-in, RevenueCat or Firebase** goes to closed testing or production:
- Play **Data safety**: switch to the answers in `docs/STORE_LISTING.md` **§5B**, and add the account deletion URL
  `https://<your site>/delete-account/`. **App access**: "Some functionality is restricted" with the instruction "Sign in
  with any Google account on the first screen."
- App Store Connect › **App Privacy**: the §5B table; **tracking: No**.

### D4. Hindi and legal answers back 🤖
Send both to Claude (B5, B6).

---

## Part E — Week 4, Mon 16 Nov → Sun 22 Nov: final builds and submission

### E1. Final presets and release check
```bash
source .venv/bin/activate
PRESETS_SIGNING_KEY='<seed>' spec/tools/build_all.sh --release      # no --allow-todo-hi: Hindi must be reviewed
python3 spec/tools/release_preflight.py --final                      # must show no BLOCKER
```
If Hindi isn't reviewed in time, tell Claude to turn the Hindi locale off for 1.0 (LAUNCH_PLAN §2 fallback) instead of
slipping the date.

### E2. Build 1.0.0
Android: `./gradlew :app:bundleRelease -Pscanfit.versionName=1.0.0 -Pscanfit.versionCode=<next number>`.
iOS: `Scripts/archive.sh --version 1.0.0 --build <next number> --upload` (with the ASC key variables from A12).

### E3. Submit iOS ⏳ (review usually 1–3 days)
App Store Connect › your app › **+ Version 1.0**: screenshots (6.9" iPhone and 13" iPad, STORE_LISTING §7),
promotional text, description, keywords, support URL, marketing URL, **privacy policy URL**, age rating questionnaire
(all "None" → 4+), **App Privacy** (D3), price **Free**, availability (India + any others), attach the build and both
in-app purchases, **App Review Information** (your contact; sign-in: "Use Sign in with Apple or Google; no demo account
needed"; the review notes from D2). Version release: **Manually release this version** (you choose launch day), and turn
on **Phased release**. **Submit for Review**.

### E4. Prepare Play production ⏳ (once production access is granted)
**Test and release › Production › Create new release** › add the 1.0.0 AAB › release notes (English + Hindi) › Review.
Don't roll out yet. Optionally set up **pre-registration**.

---

## Part F — Week 5, Mon 23 Nov → Sun 29 Nov: buffer and launch content
1. Fix any App Review rejection with Claude 🤖 (paste the rejection text).
2. 🤖 Send Claude both store links; it puts real store buttons on the website.
3. Record 6 short videos (Hindi + English), e.g. "Form rejected because of the signature? Fixed in 10 seconds", "Why your
   19 KB photo gets rejected", "Compress a certificate PDF under 200 KB". Prepare the launch posts for your groups and
   LinkedIn.

---

## Part G — Launch, Tue 1 Dec → Mon 7 Dec
1. **Android**: Production release › **Start rollout** at **10%** (Tue 1) › **25%** (Thu 3) › **50%** (Sat 5) › **100%**
   (Mon 7). Before each step check **Android vitals**: crash-free ≥ 99.5%, ANR < 0.3%, and no cluster of "portal rejected
   it" reports for one exam. If something is wrong, **Halt rollout** and tell Claude.
2. **iOS**: when approved, **Release this version**; phased release spreads it over 7 days and can be paused.
3. Every day: reply to every review (both stores), check Crashlytics, RevenueCat and Play vitals.
4. Post the videos and messages. Ask testers for an honest review (never ask for 5 stars or offer anything for one).
5. Give testers who stayed 14 days their free lifetime Pro (Play promo codes / App Store offer codes).

## Part H — After launch (weekly)
- Check new exam notifications; when one changes upload rules, tell Claude the exam and the notice link (presets are
  fixed and published within a day).
- Weekly numbers: installs, first saves, people reaching 20 saves, paywall views → purchases, crashes, ratings.
- Plan v1.1 with Claude (scan, guide sheet, live coach) before the January–March NEET/CUET season.

---

## What to send Claude, and when

| When | Send | Never send |
|---|---|---|
| A2–A4 | Name-check findings (Q1–Q5 are answered) | |
| A9 | Phone test results with platform, OS and photo source | Real documents or IDs |
| A10, A11 | "Make rc builds generous", "make the icon, feature graphic and iPhone screenshots" | |
| B1 | "Firebase files are in place" | The files' contents |
| B2 | The two public RevenueCat keys (`goog_…`, `appl_…`) | The service-account JSON, `.p8` keys |
| B5, B6 | The reviewed `hi.json`, the lawyer's answers | |
| C3, G1 | Bugs with platform and OS | |
| F | Store links, any App Review rejection text | Passwords, the presets seed, keystore passwords |
