#!/bin/bash
# Build a signed release APK — the interactive release ceremony.
#
#   ./scripts/release.sh
#
# Before building it:
#   1. checks the native build's preconditions (scripts/build-native.sh
#      --check-release: pinned toolchain, NDK, core/ clean at the recorded
#      commit) — Gradle then builds the native libs from core/ itself;
#   2. shows the current versionCode/versionName and asks for confirmation
#      (versionCode must STRICTLY INCREASE or existing installs will refuse
#      the update);
#   3. prompts for the keystore password — held in this process's environment
#      only, never written to disk, shell history, or visible in ps.
#
# Keystore path: $SPORA_KEYSTORE, default ~/secure/spora-release.jks (wired
# to the signingConfigs block in app/build.gradle.kts). After the build the
# APK's signing certificate is verified against the pinned release
# fingerprint below — the app's permanent identity, also listed in
# spora-web's assetlinks.json and in the F-Droid recipe
# (AllowedAPKSigningKeys).
#
# F-Droid rebuilds every release from its tag and publishes this APK only if
# its rebuild is identical, so a release must come from a clean tree at the
# tagged commit, built with JDK 21 (as F-Droid's build server). At the end the
# script offers to push the tag and attach the APK to a draft GitHub release:
# F-Droid downloads it from
#   https://github.com/Spora-VPN/spora-android/releases/download/v<version>/spora-<version>.apk

set -euo pipefail
cd "$(dirname "$0")/.."

RELEASE_SHA256="ba76b2331d8d67caf357257f15caa1f5700a099d7a9e9a56178d79ab2c2730d0"

KEYSTORE="${SPORA_KEYSTORE:-$HOME/secure/spora-release.jks}"
[ -f "$KEYSTORE" ] || { echo "error: keystore not found: $KEYSTORE (set SPORA_KEYSTORE)" >&2; exit 1; }

# Release builds use JDK 21, as F-Droid's build server does: another javac
# can emit different bytecode, and the rebuild would not match. It must be a
# full JDK — the apt-installed /usr/lib/jvm JDKs on this machine are
# javac-less JREs (Android Studio builds work because Studio uses its bundled
# JBR). Prefer $JAVA_HOME, then the Studio JBR, then any JDK 21.
jdk21() { [ -x "$1/bin/javac" ] && "$1/bin/javac" -version 2>&1 | grep -q '^javac 21\.'; }
if ! jdk21 "${JAVA_HOME:-/nonexistent}"; then
  for cand in "$HOME/install/android-studio/jbr" /usr/lib/jvm/*; do
    if jdk21 "$cand"; then export JAVA_HOME="$cand"; break; fi
  done
fi
jdk21 "${JAVA_HOME:-/nonexistent}" \
  || { echo "error: no JDK 21 with javac found — set JAVA_HOME to one" >&2; exit 1; }
echo "using JDK: $JAVA_HOME"

# fails early (before any prompt) on a wrong toolchain or core/ state
scripts/build-native.sh --check-release

if [ -n "$(git status --porcelain)" ]; then
  echo "warning: working tree is not clean — this build will not be reproducible from a commit"
  echo "(F-Droid could not verify it; no tag or GitHub release will be offered):"
  git status --short | head -10
  echo
fi

echo "Current version (app/build.gradle.kts):"
grep -nE '^[[:space:]]*(versionCode|versionName)' app/build.gradle.kts | sed 's/^/  /'
echo
echo "Reminder: bump versionCode (strictly increasing) and versionName BEFORE"
echo "releasing; devices only update over a lower versionCode."
VCODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
CHANGELOG="fastlane/metadata/android/en-US/changelogs/$VCODE.txt"
[ -f "$CHANGELOG" ] || echo "note: no $CHANGELOG — F-Droid shows it as this version's release notes (also ru/, be/)"
read -rp "Build the release with the version shown above? [y/N] " answer
case "$answer" in
  y|Y|yes|YES) ;;
  *) echo "aborted — edit app/build.gradle.kts and re-run"; exit 1 ;;
esac

# Native libs: Gradle's buildNativeRelease builds them from core/ during
# assembleRelease and checks the committed bindings against them.
CORE_PROVENANCE=$(git -C core rev-parse --short=12 HEAD)

read -rsp "Keystore password for $KEYSTORE: " SPORA_KEYSTORE_PASSWORD
echo
export SPORA_KEYSTORE="$KEYSTORE" SPORA_KEYSTORE_PASSWORD

./gradlew :app:assembleRelease --console=plain

APK=$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' | head -1)
[ -n "$APK" ] || { echo "error: no APK produced" >&2; exit 1; }

# Verify the APK really carries the release certificate (a mis-wired signing
# config would otherwise slip an unsigned or debug-signed build through).
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  got=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
        | grep -im1 'certificate SHA-256 digest' | grep -oE '[0-9a-f]{64}' || true)
  if [ "$got" = "$RELEASE_SHA256" ]; then
    echo "signature verified: release cert"
  else
    echo "error: APK signed with unexpected cert: ${got:-<unsigned>}" >&2
    exit 1
  fi
else
  echo "warning: apksigner not found under $SDK/build-tools — signature NOT verified"
fi

echo
echo "built: $APK"

# Offer an annotated tag for the exact source state of this build — only on
# a clean tree (a tag on a dirty build would name a commit that is not what
# shipped), and never moving an existing tag (rebuild-after-fix case).
VERSION=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)
TAG="v$VERSION"
if [ -n "$(git status --porcelain)" ]; then
  echo "note: working tree dirty — not offering tag $TAG (it would not describe this build)"
elif git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "note: tag $TAG already exists — not re-tagging (delete it first if this build supersedes it)"
else
  read -rp "Tag HEAD as $TAG (annotated, versionCode $VCODE)? [y/N] " tagit
  case "$tagit" in
    y|Y|yes|YES)
      git tag -a "$TAG" -m "spora-android $VERSION (versionCode $VCODE)

native libs built from core at: $CORE_PROVENANCE"
      echo "tagged $TAG"
      ;;
    *)
      echo "skipped — tag later with: git tag -a $TAG && git push origin $TAG"
      ;;
  esac
fi

# The F-Droid binary: the tag on GitHub, and the APK on its release. Draft,
# so publishing stays a deliberate step (after the device check below);
# F-Droid can only download it once it is published.
if [ -z "$(git status --porcelain)" ] \
   && [ "$(git rev-parse -q --verify "refs/tags/$TAG^{commit}" || true)" = "$(git rev-parse HEAD)" ]; then
  read -rp "Push $TAG and attach the APK to a draft GitHub release (needs gh)? [y/N] " gh_up
  case "$gh_up" in
    y|Y|yes|YES)
      notes=(--notes "")
      [ -f "$CHANGELOG" ] && notes=(--notes-file "$CHANGELOG")
      git push origin "$TAG"
      gh release create "$TAG" "$APK" --verify-tag --draft --title "Spora $VERSION" "${notes[@]}"
      echo "draft release created — publish it after the device check"
      ;;
    *)
      echo "skipped — later: git push origin $TAG && gh release create $TAG $APK --verify-tag --draft"
      ;;
  esac
fi

echo
echo "next:  install + sanity-check on a device (share, connect, tap an /s/ link),"
echo "       publish the GitHub release, then upload via the website admin."
