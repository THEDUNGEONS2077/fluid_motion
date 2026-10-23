#!/usr/bin/env bash
# scripts/release-android.sh: builds, checks and publishes a fluid_motion
# release (the user installs test builds from GitHub Releases, not adb).
#
# Usage:
#   scripts/release-android.sh                         # build + checks + DRAFT release
#   scripts/release-android.sh --prerelease --publish  # a published test build
#   scripts/release-android.sh --verify-only           # build + checks, nothing published
#
# Every check must pass, or nothing is published. The version comes from
# app/build.gradle.kts (versionName, versionCode); its CHANGELOG.md section
# ("## x.y.z (build N)") becomes the release notes. Publishing is public:
# run it only with the user's OK.
set -euo pipefail

# --- Options ---------------------------------------------------------------
PUBLISH=false; PRERELEASE=false; VERIFY_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --publish) PUBLISH=true ;;
    --prerelease) PRERELEASE=true ;;
    --verify-only) VERIFY_ONLY=true ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

# Run from the repo root, wherever the script is called from.
cd "$(dirname "$0")/.."

# Fail with a message (every check below uses these).
fail() { echo "✕ $*" >&2; exit 1; }
ok() { echo "✓ $*"; }

# --- Expected values (change only on purpose) -----------------------------
# The release key's certificate (keys/fluid_motion-release.jks): phones only take updates signed by it.
CERT_SHA256='8F:7D:DF:CE:20:A8:EF:40:FE:4B:5C:A5:E3:3B:B1:E7:6A:66:59:42:5F:E3:11:CE:F2:64:96:A0:70:DC:02:A3'
# The only permission a release may have: AndroidX's own, signature-level, for its internal receivers.
# (No INTERNET, nothing else: the app never goes online and reads nothing.)
ALLOWED_PERMISSIONS='com.thedungeons2077.fluidmotion.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
# Native code: only Compose's tiny path helper (no native code of our own).
ALLOWED_NATIVE='libandroidx.graphics.path.so'
# Size budget: the whole app is small; a jump means something heavy crept in.
MAX_APK_BYTES=$((5 * 1000 * 1000))

# --- Tools -------------------------------------------------------------------
BT="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)"
APKSIGNER="$BT/apksigner"; AAPT2="$BT/aapt2"
[ -x "$APKSIGNER" ] && [ -x "$AAPT2" ] || fail "Android build-tools not found (apksigner, aapt2)"
$VERIFY_ONLY || command -v gh >/dev/null || fail "GitHub CLI (gh) not installed"
[ -f keystore.properties ] || fail "keystore.properties missing: the release key is needed to sign (see RELEASING in README.md)"

# --- Version and notes ----------------------------------------------------
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
BUILD="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)"
TAG="v$VERSION"
# The CHANGELOG section for this version, without its heading.
NOTES="$(awk -v h="## $VERSION (build $BUILD)" '$0==h{f=1;next} /^## /{if(f)exit} f' CHANGELOG.md)"
[ -n "$NOTES" ] || fail "CHANGELOG.md has no section '## $VERSION (build $BUILD)'"
ok "version $VERSION (build $BUILD), notes found"

# --- Preconditions (skipped for --verify-only) ----------------------------
if ! $VERIFY_ONLY; then
  # Publish only committed, pushed code, so the tag matches the APK.
  [ -z "$(git status --porcelain)" ] || fail "uncommitted changes; commit first"
  git fetch -q origin
  [ "$(git rev-parse HEAD)" = "$(git rev-parse '@{u}')" ] || fail "HEAD isn't pushed to origin"
  ! gh release view "$TAG" >/dev/null 2>&1 || fail "release $TAG already exists (bump the version)"
  # versionCode must grow, or phones refuse the update.
  LAST_BUILD="$(git tag --list 'v*' | while read -r t; do git show "$t:app/build.gradle.kts" 2>/dev/null | sed -n 's/.*versionCode = \([0-9]*\).*/\1/p'; done | sort -n | tail -1)"
  [ -z "$LAST_BUILD" ] || [ "$BUILD" -gt "$LAST_BUILD" ] || fail "versionCode $BUILD isn't above the last released build $LAST_BUILD"
  ok "clean, pushed, new tag, build number increases"
fi

# --- Tests, the shader check, then the signed build ----------------------
LOG="$(mktemp -d)"
./gradlew -q :app:testDebugUnitTest >"$LOG/test.log" 2>&1 || fail "unit tests failed (see $LOG/test.log)"
ok "unit tests pass (and the frame dump for the shader check is fresh)"
# The shaders compiled, linked and drawn in WebGL2 (the same GLSL ES 3.00), from the frames just dumped.
(cd tools/shader-check && { [ -d node_modules ] || npm ci --no-audit --no-fund >/dev/null; } && node run.mjs) >"$LOG/shaders.log" 2>&1 || fail "shader check failed (see $LOG/shaders.log)"
ok "shaders compile, link and draw (tools/shader-check/out/)"
./gradlew -q :app:assembleRelease >"$LOG/build.log" 2>&1 || fail "release build failed (see $LOG/build.log)"
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || fail "no APK at $APK"
ok "signed release built"

# --- Verify the APK -----------------------------------------------------------
# 1. Signed with the release key (same key, or the update won't install over the last).
"$APKSIGNER" verify "$APK" || fail "signature does not verify"
SIG="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
[ "$SIG" = "$CERT_SHA256" ] || fail "signed with an unexpected key: $SIG"
ok "signed with the release key"

# 2. Exactly the allowed permissions.
PERMS="$("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\(.*\)'.*/\1/p" | sort)"
[ "$PERMS" = "$(echo "$ALLOWED_PERMISSIONS" | sort)" ] || { echo "$PERMS" >&2; fail "permissions differ from the allow-list"; }
ok "permissions match the allow-list"

# 3. No native code but the allowed, and within the size budget.
NATIVE="$(unzip -l "$APK" | sed -n 's#.* lib/[^/]*/\(.*\.so\)$#\1#p' | sort -u | tr '\n' ' ')"
[ -z "$NATIVE" ] || [ "$NATIVE" = "$ALLOWED_NATIVE " ] || fail "unexpected native libraries: $NATIVE"
APK_BYTES="$(stat -c %s "$APK")"
[ "$APK_BYTES" -lt "$MAX_APK_BYTES" ] || fail "APK is $APK_BYTES bytes (budget $MAX_APK_BYTES)"
ok "APK $APK_BYTES bytes; native: ${NATIVE:-none}"

# --- Package -------------------------------------------------------------------
mkdir -p dist
OUT="dist/fluid_motion-$TAG.apk"
cp "$APK" "$OUT"
(cd dist && sha256sum "fluid_motion-$TAG.apk" > "fluid_motion-$TAG.apk.sha256")
ok "packaged $OUT (+ .sha256)"
$VERIFY_ONLY && { echo "verify-only: nothing published"; exit 0; }

# --- Publish -------------------------------------------------------------------
FLAGS=()
$PUBLISH || FLAGS+=(--draft)
$PRERELEASE && FLAGS+=(--prerelease)
gh release create "$TAG" "$OUT" "$OUT.sha256" --title "fluid_motion $TAG (build $BUILD)" --notes "$NOTES" "${FLAGS[@]}"
$PUBLISH && ok "published $TAG" || ok "draft $TAG created: review it on GitHub, then publish"
