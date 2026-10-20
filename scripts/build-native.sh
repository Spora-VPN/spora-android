#!/bin/bash
# Build the app's native library, libspora_ffi.so, from the Rust core in the
# core/ submodule. Release builds are reproducible: F-Droid rebuilds each
# release on its own machine, at another path, and publishes our signed APK
# only if its rebuild matches it byte for byte.
#
#   scripts/build-native.sh [--abi ABI]... [--core DIR]
#       Dev build (debug profile; all four ABIs unless --abi narrows it) into
#       app/src/main/jniLibs, which only debug APKs package, and freshly
#       generated Kotlin bindings into app/src/main/java/uniffi. --core (or
#       SPORA_CORE) builds another core checkout, e.g. ../spora.
#
#   scripts/build-native.sh --release --out DIR [--ndk DIR]
#       Release build of all four ABIs into DIR/<abi>/libspora_ffi.so. Gradle
#       runs this for every release APK (task buildNativeRelease); nothing
#       else puts native code into a release. Refuses a core/ that is dirty or
#       not at the commit this checkout records, and committed bindings that
#       do not match the core.
#
#   scripts/build-native.sh --check-release
#       The release preconditions (toolchain, NDK, core/) without building.
#
#   scripts/build-native.sh --install-toolchain
#       Install the pinned Rust toolchain with the Android targets, cargo-ndk
#       and, if sdkmanager is available, the pinned NDK. Needs rustup.
#
# What makes a release build reproducible:
#   - pinned versions: Rust and cargo-ndk below; the NDK (ndkVersion) and the
#     API level (minSdk) come from app/build.gradle.kts;
#   - --remap-path-prefix for the core checkout, CARGO_HOME and the
#     toolchain's rust-src: without it, panic locations carry the build
#     machine's absolute paths (about 500 of them);
#   - the build stamp (core/spora-core/build.rs) taken from the submodule
#     commit, not from whatever git state the builder has;
#   - Cargo.lock as committed (--locked); RUSTFLAGS, compiler wrappers and
#     CARGO_PROFILE_* overrides from the environment are ignored.
# Changing a pin changes the output: update the F-Droid recipe (its ndk: line)
# in the same release.

set -euo pipefail

RUST_VERSION=1.97.1
CARGO_NDK_VERSION=4.1.2

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
BINDINGS="$ROOT/app/src/main/java/uniffi/spora_ffi/spora_ffi.kt"
ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

die() { echo "build-native: $*" >&2; exit 1; }

NDK_VERSION="$(sed -n 's/^[[:space:]]*ndkVersion = "\([^"]*\)".*/\1/p' "$GRADLE_FILE")"
MIN_SDK="$(sed -n 's/^[[:space:]]*minSdk = \([0-9]*\).*/\1/p' "$GRADLE_FILE")"
[ -n "$NDK_VERSION" ] || die "no ndkVersion in $GRADLE_FILE"
[ -n "$MIN_SDK" ] || die "no minSdk in $GRADLE_FILE"

triple_of() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    x86) echo i686-linux-android ;;
    x86_64) echo x86_64-linux-android ;;
    *) die "unknown ABI: $1" ;;
  esac
}

MODE=dev
OUT=""
NDK_ARG=""
CORE_ARG="${SPORA_CORE:-}"
ABIS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --release) MODE=release ;;
    --check-release) MODE=check ;;
    --install-toolchain) MODE=install ;;
    --out) OUT="${2:?--out needs a directory}"; shift ;;
    --ndk) NDK_ARG="${2:?--ndk needs a directory}"; shift ;;
    --core) CORE_ARG="${2:?--core needs a directory}"; shift ;;
    --abi) triple_of "${2:?--abi needs an ABI name}" >/dev/null; ABIS+=("$2"); shift ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# --- toolchain -------------------------------------------------------------

export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
# Gradle started from a desktop launcher may not have cargo on its PATH.
case ":$PATH:" in *":$CARGO_HOME/bin:"*) ;; *) PATH="$CARGO_HOME/bin:$PATH" ;; esac
export RUSTUP_TOOLCHAIN="$RUST_VERSION"
# a missing toolchain is an error with a hint, not a silent download
export RUSTUP_AUTO_INSTALL=0

ndk_revision() { sed -n 's/^Pkg\.Revision *= *//p' "$1/source.properties" 2>/dev/null; }

sdk_dirs() {
  local props="$ROOT/local.properties"
  [ -n "${ANDROID_HOME:-}" ] && echo "$ANDROID_HOME"
  [ -n "${ANDROID_SDK_ROOT:-}" ] && echo "$ANDROID_SDK_ROOT"
  [ -f "$props" ] && echo "$(sed -n 's/^sdk\.dir=//p' "$props")"
  echo "$HOME/Android/Sdk"
}

find_ndk() {
  local d
  if [ -n "$NDK_ARG" ]; then echo "$NDK_ARG"; return; fi
  while read -r d; do
    [ -n "$d" ] && [ -d "$d/ndk/$NDK_VERSION" ] && { echo "$d/ndk/$NDK_VERSION"; return; }
  done < <(sdk_dirs)
  for d in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK:-}"; do
    [ -n "$d" ] && [ "$(ndk_revision "$d")" = "$NDK_VERSION" ] && { echo "$d"; return; }
  done
  return 0
}

cargo_ndk_version() { cargo ndk --version 2>/dev/null | awk '{print $NF}'; }

install_toolchain() {
  command -v rustup >/dev/null \
    || die "rustup not found: install it from https://rustup.rs (--default-toolchain none is enough)"
  local triples=() abi sdkmanager d
  for abi in "${ALL_ABIS[@]}"; do triples+=("$(triple_of "$abi")"); done
  rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update \
    --target "$(IFS=,; echo "${triples[*]}")"
  if [ "$(cargo_ndk_version)" != "$CARGO_NDK_VERSION" ]; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
  fi
  if [ -z "$(find_ndk)" ]; then
    sdkmanager="$(command -v sdkmanager || true)"
    while read -r d; do
      [ -z "$sdkmanager" ] && [ -x "$d/cmdline-tools/latest/bin/sdkmanager" ] \
        && sdkmanager="$d/cmdline-tools/latest/bin/sdkmanager"
    done < <(sdk_dirs)
    [ -n "$sdkmanager" ] || die "NDK $NDK_VERSION missing and no sdkmanager to install it"
    yes | "$sdkmanager" --install "ndk;$NDK_VERSION" >/dev/null
  fi
  echo "toolchain ready: Rust $RUST_VERSION, cargo-ndk $CARGO_NDK_VERSION, NDK $NDK_VERSION"
}

check_toolchain() {
  local hint="run: scripts/build-native.sh --install-toolchain" installed abi
  command -v rustup >/dev/null || die "rustup not found; $hint"
  rustc --version 2>/dev/null | grep -q "^rustc $RUST_VERSION " \
    || die "Rust $RUST_VERSION is not installed; $hint"
  installed="$(rustup target list --installed)"
  for abi in "${ALL_ABIS[@]}"; do
    grep -qx "$(triple_of "$abi")" <<<"$installed" \
      || die "Rust $RUST_VERSION lacks target $(triple_of "$abi"); $hint"
  done
  [ "$(cargo_ndk_version)" = "$CARGO_NDK_VERSION" ] \
    || die "need cargo-ndk $CARGO_NDK_VERSION, found '$(cargo_ndk_version)'; $hint"
  NDK="$(find_ndk)"
  [ -n "$NDK" ] || die "NDK $NDK_VERSION not found (looked under ANDROID_HOME, local.properties, ~/Android/Sdk); $hint"
  [ "$(ndk_revision "$NDK")" = "$NDK_VERSION" ] \
    || die "$NDK is NDK '$(ndk_revision "$NDK")', need $NDK_VERSION"
  # cargo-ndk reads any of these; all name the pinned NDK
  export ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_PATH="$NDK"
}

if [ "$MODE" = install ]; then install_toolchain; exit 0; fi

# --- core ------------------------------------------------------------------

CORE="$ROOT/core"
if [ -n "$CORE_ARG" ]; then
  [ "$MODE" = dev ] || die "release builds use the core/ submodule; drop --core/SPORA_CORE"
  CORE="$CORE_ARG"
fi
[ -f "$CORE/Cargo.toml" ] || {
  [ "$CORE" = "$ROOT/core" ] && die "core/ is empty; run: git submodule update --init"
  die "no Cargo.toml in $CORE"
}
CORE="$(cd "$CORE" && pwd -P)"

check_release_core() {
  local recorded actual
  recorded="$(git -C "$ROOT" rev-parse HEAD:core 2>/dev/null)" \
    || die "release builds need a git checkout that records the core/ submodule"
  actual="$(git -C "$CORE" rev-parse HEAD)"
  [ "$recorded" = "$actual" ] || die "core/ is at ${actual:0:12} but this commit records ${recorded:0:12};
  commit the submodule bump, or run: git submodule update core"
  [ -z "$(git -C "$CORE" status --porcelain --untracked-files=no)" ] \
    || die "core/ has uncommitted changes; a release must build the recorded commit as is"
}

check_toolchain
if [ "$MODE" = check ]; then
  check_release_core
  echo "release preconditions OK: core $(git -C "$CORE" rev-parse --short=12 HEAD), Rust $RUST_VERSION, cargo-ndk $CARGO_NDK_VERSION, NDK $NDK_VERSION"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# uniffi-bindgen reads the component metadata from the unstripped library.
generate_bindings() { # profile, ABI, out dir
  (cd "$CORE" && cargo run --locked --quiet --bin uniffi-bindgen -- generate \
    --library "$CARGO_TARGET_DIR/$(triple_of "$2")/$1/libspora_ffi.so" \
    --language kotlin --out-dir "$3")
  [ -s "$3/uniffi/spora_ffi/spora_ffi.kt" ] || die "uniffi-bindgen produced no spora_ffi.kt"
}

TARGETS=()

if [ "$MODE" = release ]; then
  [ -n "$OUT" ] || die "--release needs --out DIR"
  [ ${#ABIS[@]} -eq 0 ] || die "release builds always build all four ABIs"
  check_release_core

  # Only what is set here reaches the compiler.
  unset RUSTFLAGS CARGO_BUILD_RUSTFLAGS RUSTC_WRAPPER CARGO_BUILD_RUSTC_WRAPPER \
        RUSTC_WORKSPACE_WRAPPER CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER CARGO_BUILD_TARGET_DIR
  while read -r var; do unset "$var"; done < <(compgen -e | grep -E '^CARGO_PROFILE_|^CARGO_TARGET_.*_RUSTFLAGS$' || true)
  export CARGO_INCREMENTAL=0
  export CARGO_TARGET_DIR="$CORE/target"
  export SPORA_BUILD_COMMIT="$(git -C "$CORE" rev-parse --short=12 HEAD)"
  export SPORA_BUILD_DIRTY=0

  SYSROOT="$(rustc --print sysroot)"
  RUSTC_HASH="$(rustc -vV | sed -n 's/^commit-hash: //p')"
  REMAP=("$CORE=/spora" "$CARGO_HOME=/cargo" "$SYSROOT/lib/rustlib/src/rust=/rustc/$RUSTC_HASH")
  # the same paths as the OS reports them, if a symlink is involved
  real_home="$(cd "$CARGO_HOME" && pwd -P)"; real_sysroot="$(cd "$SYSROOT" && pwd -P)"
  [ "$real_home" = "$CARGO_HOME" ] || REMAP+=("$real_home=/cargo")
  [ "$real_sysroot" = "$SYSROOT" ] || REMAP+=("$real_sysroot/lib/rustlib/src/rust=/rustc/$RUSTC_HASH")
  flags=()
  for r in "${REMAP[@]}"; do flags+=("--remap-path-prefix=$r"); done
  # 0x1f-separated: paths with spaces survive
  export CARGO_ENCODED_RUSTFLAGS="$(IFS=$'\x1f'; echo "${flags[*]}")"

  for abi in "${ALL_ABIS[@]}"; do TARGETS+=(-t "$abi"); done
  mkdir -p "$OUT"
  for abi in "${ALL_ABIS[@]}"; do rm -f "$OUT/$abi/libspora_ffi.so"; done
  (cd "$CORE" && cargo ndk "${TARGETS[@]}" --platform "$MIN_SDK" -o "$OUT" \
    build -p spora-ffi --release --locked)
  for abi in "${ALL_ABIS[@]}"; do
    [ -f "$OUT/$abi/libspora_ffi.so" ] || die "missing $OUT/$abi/libspora_ffi.so"
  done

  # A library whose bindings differ crashes at first use (UniFFI's checksum
  # handshake), so a mismatch fails the release here.
  generate_bindings release "${ALL_ABIS[0]}" "$TMP"
  diff -q "$TMP/uniffi/spora_ffi/spora_ffi.kt" "$BINDINGS" >/dev/null || die \
    "the committed bindings do not match core/ at ${SPORA_BUILD_COMMIT}; regenerate them
  with scripts/build-native.sh and commit app/src/main/java/uniffi"
  echo "release native libs built: core $SPORA_BUILD_COMMIT, Rust $RUST_VERSION, NDK $NDK_VERSION, API $MIN_SDK"
  exit 0
fi

# --- dev build ---------------------------------------------------------------

[ ${#ABIS[@]} -gt 0 ] || ABIS=("${ALL_ABIS[@]}")
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$CORE/target}"
JNI="$ROOT/app/src/main/jniLibs"
for abi in "${ABIS[@]}"; do TARGETS+=(-t "$abi"); done
(cd "$CORE" && cargo ndk "${TARGETS[@]}" --platform "$MIN_SDK" -o "$JNI" build -p spora-ffi --locked)

# Libraries of ABIs not built now came from an older core and would fail
# UniFFI's checksum check against the bindings generated below.
for dir in "$JNI"/*/; do
  abi="$(basename "$dir")"
  case " ${ABIS[*]} " in
    *" $abi "*) ;;
    *) if [ -f "$dir/libspora_ffi.so" ]; then
         rm -f "$dir/libspora_ffi.so"
         echo "removed stale $abi/libspora_ffi.so (build without --abi for all four ABIs)" >&2
       fi ;;
  esac
done

generate_bindings debug "${ABIS[0]}" "$TMP"
cp -r "$TMP/uniffi" "$ROOT/app/src/main/java/"
echo "dev native libs (${ABIS[*]}) and bindings installed from $CORE"
