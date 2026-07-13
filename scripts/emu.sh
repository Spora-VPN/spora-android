#!/usr/bin/env bash
# Dev-loop harness: boot a headless emulator, install Spora, drive the UI,
# inject debug states, and capture screenshots / UI dumps for inspection.
# See .claude/skills/emulator/SKILL.md for recipes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
# Pin adb (and gradle installDebug) to the emulator so an attached USB phone
# can't be mistaken for it or receive commands meant for it.
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
JAVA_HOME_DEFAULT="$HOME/install/android-studio/jbr"
AVD="${AVD:-Pixel_6_root}"   # API 36 google_apis (the API 33 AVDs have no system image installed)
PKG="to.spora.android"
OUTDIR="$ROOT/.emu"
mkdir -p "$OUTDIR"

die() { echo "emu.sh: $*" >&2; exit 1; }

booted() { [ "$($ADB get-state 2>/dev/null || true)" = "device" ]; }

cmd_boot() {
    if booted; then echo "emulator already running"; return; fi
    echo "booting $AVD headless..."
    # -no-snapshot-load: the saved default_boot snapshots are stale/corrupt and
    # wedge QEMU (virtio-net state error); always cold boot.
    nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot-load -gpu swiftshader_indirect >"$OUTDIR/emulator.log" 2>&1 &
    "$ADB" wait-for-device
    local i=0
    until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 2; i=$((i+2)); [ $i -ge 180 ] && die "boot timed out after ${i}s (see $OUTDIR/emulator.log)"
    done
    echo "booted in ~${i}s"
}

cmd_install() {
    (cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}" ./gradlew -q :app:installDebug)
    echo "installed"
}

cmd_launch() {
    "$ADB" shell am start -W -n "$PKG/.MainActivity" | grep -E "Status|TotalTime" || true
}

cmd_shot() {
    local name="${1:-shot-$(date +%H%M%S)}"
    "$ADB" exec-out screencap -p > "$OUTDIR/$name.png"
    echo "$OUTDIR/$name.png"
}

cmd_dump() {
    "$ADB" shell uiautomator dump /sdcard/window_dump.xml >/dev/null
    "$ADB" pull /sdcard/window_dump.xml "$OUTDIR/ui-dump.xml" >/dev/null
    echo "$OUTDIR/ui-dump.xml"
}

# Inject app state via the debug-only receiver. Examples:
#   emu.sh state seed --ei shares 2 --ei uses 2
#   emu.sh state connect-failed --es msg "relay unreachable"
#   emu.sh state share-started --es id debug-share-1
cmd_state() {
    local c="$1"; shift || true
    # adb shell flattens argv into one string for the device shell, so each
    # extra must be re-quoted or multi-word values get split.
    local q=""
    local a; for a in "$@"; do q="$q '${a//\'/\'\\\'\'}'"; done
    "$ADB" shell "am broadcast -n $PKG/.DebugStateReceiver -a $PKG.debug.STATE --es cmd '$c'$q" \
        | grep -v "^Broadcasting" || true
}

cmd_dark()      { case "${1:?on|off}" in on) v=yes;; off) v=no;; *) die "dark on|off";; esac; "$ADB" shell cmd uimode night "$v"; }
cmd_fontscale() { "$ADB" shell settings put system font_scale "${1:?e.g. 1.3}"; }
cmd_locale()    { "$ADB" shell cmd locale set-app-locales "$PKG" --user current --locales "${1:?e.g. ru}"; }
cmd_rotate()    { "$ADB" shell settings put system accelerometer_rotation 0; "$ADB" shell settings put system user_rotation "${1:?0|1|2|3}"; }
cmd_kill()      { "$ADB" shell am force-stop "$PKG"; echo "killed $PKG"; }
cmd_notif()     { "$ADB" shell dumpsys notification --noredact 2>/dev/null | grep -A 12 "pkg=$PKG" || echo "(no notifications from $PKG)"; }
cmd_vpn()       { "$ADB" shell ip addr show tun0 2>/dev/null || echo "(no tun0)"; "$ADB" shell dumpsys connectivity | grep -i "vpn" | head -5 || true; }
cmd_log()       { "$ADB" logcat -d -t "${1:-100}" --pid="$("$ADB" shell pidof -s $PKG | tr -d '\r')" 2>/dev/null || "$ADB" logcat -d -t "${1:-100}" | grep -i spora; }
cmd_stop()      { "$ADB" emu kill; }

usage() {
    sed -n 's/^cmd_\([a-z]*\)().*/  \1/p' "$0"
    echo "Plus raw passthrough: tap X Y | swipe X1 Y1 X2 Y2 [ms] | key KEYCODE | text 'STRING'"
}

case "${1:-}" in
    boot|install|launch|shot|dump|state|dark|fontscale|locale|rotate|kill|notif|vpn|log|stop)
        c="$1"; shift; "cmd_$c" "$@" ;;
    tap)   shift; "$ADB" shell input tap "$@" ;;
    swipe) shift; "$ADB" shell input swipe "$@" ;;
    key)   shift; "$ADB" shell input keyevent "$@" ;;
    text)  shift; "$ADB" shell input text "$(printf '%s' "$*" | sed 's/ /%s/g')" ;;
    *) usage; exit 1 ;;
esac
