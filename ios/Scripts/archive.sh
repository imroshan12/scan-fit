#!/usr/bin/env bash
# Archive the Release build and export an App Store Connect IPA (docs/RELEASE_CHECKLIST.md).
#
#   Scripts/archive.sh --no-sign                    compile + verify the archive, no signing, no account needed
#   DEVELOPMENT_TEAM=ABCDE12345 Scripts/archive.sh  signed IPA in build/export (Xcode must be signed in to that team)
#   Scripts/archive.sh --upload                     also upload to App Store Connect (TestFlight)
#   --version 1.2.3   --build 57                    override MARKETING_VERSION / CURRENT_PROJECT_VERSION
#   --free-saves 200                                FREE_SAVES_TOTAL for a test build (ALGORITHMS 14.2; default 20)
#
# The team may also live in ios/Config/Local.xcconfig. CI authenticates with an App Store Connect API key instead of an
# Xcode login: ASC_KEY_PATH (the .p8 file), ASC_KEY_ID, ASC_ISSUER_ID.
# Needs spec/dist built once with spec/tools/build_all.sh (presets are not signed; the app checks their format). No Python
# runs in the build itself.
set -euo pipefail
cd "$(dirname "$0")/.."

SIGN=1 UPLOAD=0 VERSION="" BUILD="" FREE_SAVES=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-sign) SIGN=0 ;;
    --upload) UPLOAD=1 ;;
    --version) VERSION="${2:?--version needs a value}"; shift ;;
    --build) BUILD="${2:?--build needs a value}"; shift ;;
    --free-saves) FREE_SAVES="${2:?--free-saves needs a value}"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
if [[ -n "${VERSION}" && ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  # The App Store only accepts dotted numbers; a pre-release suffix (1.0.0-rc1) belongs in the tag, not in CFBundleShortVersionString.
  echo "error: --version must be numeric x.y.z (App Store rule), got '${VERSION}'" >&2; exit 2
fi
if [[ -n "${BUILD}" && ! "${BUILD}" =~ ^[0-9]+$ ]]; then echo "error: --build must be an integer" >&2; exit 2; fi
if [[ -n "${FREE_SAVES}" && ! "${FREE_SAVES}" =~ ^[1-9][0-9]*$ ]]; then echo "error: --free-saves must be a positive integer" >&2; exit 2; fi
if [[ "${UPLOAD}" == 1 && "${SIGN}" == 0 ]]; then echo "error: --upload needs a signed build" >&2; exit 2; fi

OUT="build"
ARCHIVE="${OUT}/ScanFit.xcarchive"
rm -rf "${OUT}"; mkdir -p "${OUT}"

xcodegen generate

overrides=()
[[ -n "${VERSION}" ]] && overrides+=("MARKETING_VERSION=${VERSION}")
[[ -n "${BUILD}" ]] && overrides+=("CURRENT_PROJECT_VERSION=${BUILD}")
[[ -n "${FREE_SAVES}" ]] && overrides+=("FREE_SAVES_TOTAL=${FREE_SAVES}")
[[ -n "${DEVELOPMENT_TEAM:-}" ]] && overrides+=("DEVELOPMENT_TEAM=${DEVELOPMENT_TEAM}")

auth=()
if [[ -n "${ASC_KEY_PATH:-}" ]]; then
  auth=(-allowProvisioningUpdates -authenticationKeyPath "${ASC_KEY_PATH}" -authenticationKeyID "${ASC_KEY_ID:?ASC_KEY_ID}" -authenticationKeyIssuerID "${ASC_ISSUER_ID:?ASC_ISSUER_ID}")
fi

if [[ "${SIGN}" == 0 ]]; then
  signing=(CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO)
else
  if [[ -z "${DEVELOPMENT_TEAM:-}" ]] && ! grep -qE '^DEVELOPMENT_TEAM *= *[A-Z0-9]{10}' Config/Local.xcconfig 2>/dev/null; then
    echo "error: no signing team. Set DEVELOPMENT_TEAM=<10-char Team ID> or create ios/Config/Local.xcconfig (Local.xcconfig.example)." >&2
    exit 1
  fi
  signing=()
fi

xcodebuild archive -scheme ScanFit -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "${ARCHIVE}" ${auth[@]+"${auth[@]}"} ${signing[@]+"${signing[@]}"} ${overrides[@]+"${overrides[@]}"}

# Whatever the signing mode, check what actually went into the archive.
APP="${ARCHIVE}/Products/Applications/ScanFit.app"
plist() { /usr/libexec/PlistBuddy -c "Print :$1" "${APP}/Info.plist"; }
echo "archive: $(plist CFBundleIdentifier) $(plist CFBundleShortVersionString) ($(plist CFBundleVersion)), free saves $(plist MRDFreeSavesTotal)"
[[ -s "${APP}/presets.json" ]] || { echo "error: the archive has no presets.json (the embed build phase did not run)" >&2; exit 1; }
[[ -d "${ARCHIVE}/dSYMs/ScanFit.app.dSYM" ]] || { echo "error: no dSYM in the archive (crash reports would be unsymbolicated)" >&2; exit 1; }
[[ "${SIGN}" == 1 ]] || { echo "unsigned archive verified: ${ARCHIVE}"; exit 0; }

TEAM="${DEVELOPMENT_TEAM:-$(sed -nE 's/^DEVELOPMENT_TEAM *= *([A-Z0-9]{10}).*/\1/p' Config/Local.xcconfig | head -1)}"
DEST=export; [[ "${UPLOAD}" == 1 ]] && DEST=upload
cat > "${OUT}/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>${DEST}</string>
  <key>teamID</key><string>${TEAM}</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>stripSwiftSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
PLIST
xcodebuild -exportArchive -archivePath "${ARCHIVE}" -exportPath "${OUT}/export" \
  -exportOptionsPlist "${OUT}/ExportOptions.plist" ${auth[@]+"${auth[@]}"}
echo "done: ${OUT}/export ($([[ "${UPLOAD}" == 1 ]] && echo 'uploaded to App Store Connect' || echo 'IPA only, not uploaded'))"
