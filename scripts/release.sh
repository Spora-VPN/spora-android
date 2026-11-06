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
# A release is five APKs: spora-<version>.apk (universal, for GitHub and the
# website) and spora-<version>-<abi>.apk for each of the four ABIs, each with
# its own versionCode (app/build.gradle.kts). F-Droid rebuilds every ABI's
# APK from the tag and publishes ours only if its rebuild is identical, so a
# release must come from a clean tree at the tagged commit, built with JDK 21
# (as F-Droid's build server). At the end the script offers to push the tag
# and attach the APKs to a draft GitHub release: F-Droid downloads them from
#   https://github.com/Spora-VPN/spora-android/releases/download/v<version>/spora-<version>-<abi>.apk

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
VERSION=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)
# Release notes are written once per locale, as changelogs/<versionCode>.txt
# (also the GitHub release's notes). F-Droid looks them up by each APK's own
# code, <versionCode>1 to <versionCode>4, so each needs a copy under those.
CHANGELOG="fastlane/metadata/android/en-US/changelogs/$VCODE.txt"
for loc in fastlane/metadata/android/*/; do
  notes="${loc%/}/changelogs/$VCODE.txt"
  if [ ! -f "$notes" ]; then
    echo "note: no $notes — this version's release notes"
    continue
  fi
  for n in 1 2 3 4; do
    if ! cmp -s "$notes" "${notes%.txt}$n.txt"; then
      echo "note: ${notes%.txt}$n.txt is missing or differs from $VCODE.txt (F-Droid's notes for that ABI's APK);"
      echo "      for n in 1 2 3 4; do cp $notes ${notes%.txt}\$n.txt; done"
      break
    fi
  done
done
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

# From scratch, as F-Droid builds: an incremental Kotlin/Compose compile can
# emit different bytecode than a clean one (Compose's $changed masks did in
# v0.3.0's first APK), and the rebuild would not match.
./gradlew clean --console=plain
./gradlew :app:assembleRelease --console=plain

OUTDIR=app/build/outputs/apk/release
APKS=("$OUTDIR/spora-$VERSION.apk")
for abi in arm64-v8a armeabi-v7a x86 x86_64; do APKS+=("$OUTDIR/spora-$VERSION-$abi.apk"); done
for apk in "${APKS[@]}"; do
  [ -f "$apk" ] || { echo "error: $apk was not produced" >&2; exit 1; }
done

# Verify every APK really carries the release certificate (a mis-wired
# signing config would otherwise slip an unsigned or debug-signed build
# through).
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  for apk in "${APKS[@]}"; do
    got=$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
          | grep -im1 'certificate SHA-256 digest' | grep -oE '[0-9a-f]{64}' || true)
    if [ "$got" != "$RELEASE_SHA256" ]; then
      echo "error: $apk signed with unexpected cert: ${got:-<unsigned>}" >&2
      exit 1
    fi
  done
  echo "signatures verified: release cert on all ${#APKS[@]} APKs"
else
  echo "warning: apksigner not found under $SDK/build-tools — signatures NOT verified"
fi

echo
echo "built:"
printf '  %s\n' "${APKS[@]}"

# Offer an annotated tag for the exact source state of this build — only on
# a clean tree (a tag on a dirty build would name a commit that is not what
# shipped), and never moving an existing tag (rebuild-after-fix case).
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

# The F-Droid binaries: the tag on GitHub, and the APKs on its release.
# Draft, so publishing stays a deliberate step (after the device check and
# the F-Droid rebuild check below); F-Droid can only download them once it is
# published.
if [ -z "$(git status --porcelain)" ] \
   && [ "$(git rev-parse -q --verify "refs/tags/$TAG^{commit}" || true)" = "$(git rev-parse HEAD)" ]; then
  read -rp "Push $TAG and attach the APKs to a draft GitHub release (needs gh)? [y/N] " gh_up
  case "$gh_up" in
    y|Y|yes|YES)
      notes=(--notes "")
      [ -f "$CHANGELOG" ] && notes=(--notes-file "$CHANGELOG")
      git push origin "$TAG"
      gh release create "$TAG" "${APKS[@]}" --verify-tag --draft --title "Spora $VERSION" "${notes[@]}"
      echo "draft release created — publish it after the device check"
      ;;
    *)
      echo "skipped — later: git push origin $TAG && gh release create $TAG ${APKS[*]} --verify-tag --draft"
      ;;
  esac
fi

echo
echo "next:  install + sanity-check on a device (share, connect, tap an /s/ link),"
echo "       check that F-Droid's rebuild of each ABI's APK matches the draft's,"
echo "       publish the GitHub release, then upload spora-$VERSION.apk via the website admin."
