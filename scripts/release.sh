#!/bin/bash
# Build a signed release APK — the interactive release ceremony.
#
#   ./scripts/release.sh
#
# Before building it:
#   1. shows the current versionCode/versionName and asks for confirmation
#      (versionCode must STRICTLY INCREASE or existing installs will refuse
#      the update);
#   2. prompts for the keystore password — held in this process's environment
#      only, never written to disk, shell history, or visible in ps.
#
# Keystore path: $SPORA_KEYSTORE, default ~/secure/spora-release.jks (wired
# to the signingConfigs block in app/build.gradle.kts). After the build the
# APK's signing certificate is verified against the pinned release
# fingerprint below — the app's permanent identity, also listed in
# spora-web's assetlinks.json.

set -euo pipefail
cd "$(dirname "$0")/.."

RELEASE_SHA256="ba76b2331d8d67caf357257f15caa1f5700a099d7a9e9a56178d79ab2c2730d0"

KEYSTORE="${SPORA_KEYSTORE:-$HOME/secure/spora-release.jks}"
[ -f "$KEYSTORE" ] || { echo "error: keystore not found: $KEYSTORE (set SPORA_KEYSTORE)" >&2; exit 1; }

# Gradle needs a full JDK. The apt-installed /usr/lib/jvm default on this
# machine is a javac-less JRE (Android Studio builds work because Studio
# uses its bundled JBR) — so prefer an explicit JAVA_HOME only if it really
# has javac, then fall back to the Studio JBR, then any real JDK.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/javac" ]; then
  for cand in "$HOME/install/android-studio/jbr" /usr/lib/jvm/*; do
    if [ -x "$cand/bin/javac" ]; then export JAVA_HOME="$cand"; break; fi
  done
fi
[ -x "${JAVA_HOME:-/nonexistent}/bin/javac" ] \
  || { echo "error: no JDK with javac found — set JAVA_HOME to a full JDK" >&2; exit 1; }
echo "using JDK: $JAVA_HOME"

if [ -n "$(git status --porcelain)" ]; then
  echo "warning: working tree is not clean — this build will not be reproducible from a commit:"
  git status --short | head -10
  echo
fi

echo "Current version (app/build.gradle.kts):"
grep -nE '^[[:space:]]*(versionCode|versionName)' app/build.gradle.kts | sed 's/^/  /'
echo
echo "Reminder: bump versionCode (strictly increasing) and versionName BEFORE"
echo "releasing; devices only update over a lower versionCode."
read -rp "Build the release with the version shown above? [y/N] " answer
case "$answer" in
  y|Y|yes|YES) ;;
  *) echo "aborted — edit app/build.gradle.kts and re-run"; exit 1 ;;
esac

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
VCODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
TAG="v$VERSION"
if [ -n "$(git status --porcelain)" ]; then
  echo "note: working tree dirty — not offering tag $TAG (it would not describe this build)"
elif git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "note: tag $TAG already exists — not re-tagging (delete it first if this build supersedes it)"
else
  read -rp "Tag HEAD as $TAG (annotated, versionCode $VCODE)? [y/N] " tagit
  case "$tagit" in
    y|Y|yes|YES)
      CORE_HEAD=$(git -C ../spora rev-parse --short HEAD 2>/dev/null || echo unknown)
      git tag -a "$TAG" -m "spora-android $VERSION (versionCode $VCODE)

native libs built from core checkout at: $CORE_HEAD"
      echo "tagged $TAG — push with: git push origin $TAG"
      ;;
    *)
      echo "skipped — tag later with: git tag -a $TAG && git push origin $TAG"
      ;;
  esac
fi

echo
echo "next:  install + sanity-check on a device (share, connect, tap an /s/ link),"
echo "       then upload via the website admin."
