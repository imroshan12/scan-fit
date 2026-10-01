# Release checklist — what you do, and where each placeholder lives

All release *code* is in place. What is left is material only you can create: keys, store accounts, ids. Nothing
below is committed to the repo; templates end in `.example`.

**Ask the repo what is still missing** (read-only, prints no secret):

```bash
python3 spec/tools/release_preflight.py                 # both platforms; --platform android|ios; --final for a production release
```

## 0. Your tools
- **Android Studio Quail 4 | 2026.1.4 or newer.** The project builds with AGP 9.4, and older Studio versions refuse to sync it
  ("incompatible version of the Android Gradle plugin"). Android Studio 2024.2 (Ladybug) stops at AGP 8.8. Command-line builds
  (`./gradlew`) never needed Studio.
- Xcode 26 (iOS) and `brew install xcodegen`.
- **Python is not needed to build.** Gradle and Xcode builds, archives and `ios/Scripts/archive.sh` run no Python: Android verifies
  the presets signature with the JDK, iOS with `ios/Scripts/verify_presets.swift` (CryptoKit). Python is only for the spec tools
  that *produce* inputs: `spec/tools/build_all.sh` (validate, bundle and sign `spec/dist`, once per presets change), key generation,
  and the preflight. Those need `cryptography`, `jsonschema` and `pillow`. macOS Python is "externally managed", so a plain
  `pip install` fails; use a virtual environment (`.venv/` is git-ignored) and activate it in the shell where you run them:
  ```bash
  python3 -m venv .venv && source .venv/bin/activate
  pip install -r spec/tools/requirements.txt
  ```
  Without it the tools stop with these same instructions instead of a traceback.

## 1. Placeholders to replace

| What | Where | Replace with |
|---|---|---|
| **Presets signing key** (private half) | GitHub secret `PRESETS_SIGNING_KEY`, plus an offline backup | output of `python3 spec/tools/build_presets.py --genkey` (run it in the venv from section 0; it prints the key once, so copy it straight into your password manager) |
| **Presets public key** | `spec/signing/prod_public_key.b64` (the file does not exist yet; builds fail without it, on purpose) | the PUBLIC line from the same `--genkey` run, one line, then commit it |
| **Android upload key** | `android/keystore.properties` (copy `keystore.properties.example`; git-ignored) | your keystore path, passwords and alias (create it with the `keytool` line in the example) |
| **Android applicationId** | `android/app/build.gradle.kts`, `applicationId` (currently `app.scanfit`) | your permanent id. Only this line: the Kotlin `namespace` and package names stay as they are |
| **iOS bundle id** | `ios/project.yml`, `APP_BUNDLE_ID` (currently `app.scanfit.ios`); the test target follows | your permanent id |
| **iOS signing team** | `ios/Config/Local.xcconfig` (copy `Local.xcconfig.example`; git-ignored) | your 10-character Team ID |
| **Release version** | `android/gradle.properties` `scanfit.versionName` and `ios/project.yml` `MARKETING_VERSION` (both `0.1.0`) | the same `x.y.z` in both. CI overrides both from the tag |

The two ids are permanent once published. Pick them once and keep Android and iOS aligned if you want matching store listings.
Keep the keystore, its passwords and the presets seed in a password manager, outside the repo. **Losing the presets seed means
shipping a new app build with a new public key** (`spec/signing/README.md`).

## 2. Build a release locally

```bash
# presets signed with YOUR production key (pre-release: add --allow-todo-hi, see section 4)
PRESETS_SIGNING_KEY=<seed> spec/tools/build_all.sh --release --allow-todo-hi

# Android: signed AAB for Play (needs android/keystore.properties). Output: android/app/build/outputs/bundle/release/
cd android && ./gradlew :app:bundleRelease -Pscanfit.versionName=1.0.0 -Pscanfit.versionCode=1
#   also :app:assembleRelease for an APK; mapping.txt for R8 de-obfuscation is in build/outputs/mapping/release/

# iOS: Release archive + App Store Connect IPA (needs your Team ID; Xcode signed in to that team). Output: ios/build/export
cd ios && DEVELOPMENT_TEAM=ABCDE12345 Scripts/archive.sh --version 1.0.0 --build 1
#   add --upload to push to TestFlight; --no-sign only compiles and verifies the archive (no account needed)
```

What the build refuses on purpose: embedding the public DEV presets key (Android task and iOS script), a presets bundle that does
not verify against the key being embedded, a signed `bundleRelease` without the upload key (it never falls back to the debug key),
and a non-semver version.

## 3. Release from CI (tags)

| Tag | Result |
|---|---|
| `android-v1.2.3-rc1` | pre-release: signed AAB + APK + `mapping.txt` as workflow artifacts. Machine-drafted Hindi allowed |
| `android-v1.2.3` | final: same, but any `TODO_HI` Hindi string fails the build |
| `ios-v1.2.3-rc1` / `ios-v1.2.3` | archive, sign through the App Store Connect API key, upload to TestFlight. Same Hindi rule. The App Store version is `1.2.3` (it takes digits only); the build number is the CI run number |

```bash
git tag android-v0.1.0-rc1 && git push origin android-v0.1.0-rc1
```

GitHub **secrets** to add (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `PRESETS_SIGNING_KEY` | presets seed (section 1) |
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | `base64 -i scanfit-upload.jks \| pbcopy` |
| `ANDROID_UPLOAD_STORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD` | the keystore values |
| `APPLE_TEAM_ID` | 10-character Team ID |
| `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64` | an App Store Connect API key (Users and Access > Integrations; role App Manager), `.p8` base64-encoded |

GitHub **environments** (with required reviewers, so a stray tag cannot ship): `presets-production`, `android-release`, `ios-release`.

Every pull request also builds the **release configuration** with a throwaway key (`spec/tools/rehearsal_key.sh`, CI only): R8 and
release lint on Android, the Release archive on iOS. Release-only breakage shows up in review, not on tag day.

## 4. Pre-release versus final (Hindi)
All Hindi strings are machine-drafted (`TODO_HI:` prefix, 102 of them today). The app shows them without the prefix, and iOS flags
them `needs_review`. That is acceptable for the internal and closed-testing tracks (ROADMAP Phase 3 starts the 12-tester run) and not for
production: `build_all.sh --release` fails on any `TODO_HI`, and `--allow-todo-hi` (used automatically for `-rc`/`-beta` tags) lifts
that. Clear the prefixes after a native-speaker review, then tag a final release.

## 5. Store-side one-time setup (yours)
- **Google Play:** create the app (the id from section 1 is now permanent), keep **Play App Signing** on (your keystore is only the
  *upload* key and Google can reset it), complete Data safety (no data collected today; revisit when Phase 4 adds Firebase and ads),
  content rating, target audience, then upload the first AAB **by hand** (Internal testing > Create release). Google opens the publishing
  API only after that. CI therefore leaves the AAB as an artifact instead of uploading it.
- **App Store Connect:** create the app record with the iOS id, fill the privacy "nutrition label" to match `PrivacyInfo.xcprivacy`
  (no tracking, nothing collected today), add the API key from section 3, then a tag upload appears in TestFlight. Encryption export
  compliance is already declared in `Info.plist` (`ITSAppUsesNonExemptEncryption = false`).
- Store text and screenshots must carry no government emblem, logo or affiliation wording (CLAUDE.md rule 7).

## 6. Verified, and not
Verified locally (2026-10-01) with a throwaway presets key and a throwaway keystore:
- Android `bundleRelease` / `assembleRelease` signed with an upload key from the environment; `apksigner` and `jarsigner` verify them;
  `versionName`/`versionCode` come from the `-P` flags; the R8 release APK runs on an API 35 emulator and shows the verified presets.
- `bundleRelease` stops without an upload key; `assembleRelease` then yields an unsigned APK; the dev key in the prod slot is refused.
- iOS Release archive (unsigned): warnings-as-errors, dSYM, version and build number from the flags, dev key refused.
- `release_preflight.py` (12 tests, mutation-checked) and the `build_all.sh` flags.

**Not verified** (they need your credentials): a signed iOS archive/export and the TestFlight upload, the CI release jobs and the
secret wiring, and the Play upload (manual by design). Expect to iterate on the first `-rc1` tag; send me the failing step.
