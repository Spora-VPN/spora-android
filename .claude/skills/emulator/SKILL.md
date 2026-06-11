---
name: emulator
description: Verify Spora changes visually and behaviorally without manual testing — boot a headless Android emulator, install the app, inject any UI state via the debug receiver, drive the UI over adb, and read screenshots/UI dumps. Use whenever a UI/UX or service change needs to be seen working, or to reproduce review findings (dark mode, locales, errors, notifications, VPN state).
---

# Spora emulator dev loop

All commands go through `scripts/emu.sh` (no env setup needed; it locates the SDK and JAVA_HOME itself). Artifacts (screenshots, UI dumps, emulator log) land in `.emu/` at the repo root.

## Standard verification loop

```bash
scripts/emu.sh boot        # idempotent; ~60-90s cold, instant if already up
scripts/emu.sh install     # gradle :app:installDebug
scripts/emu.sh launch
scripts/emu.sh state seed --ei shares 2 --ei uses 2   # fake saved connections
scripts/emu.sh shot baseline                          # → .emu/baseline.png
```

Then **Read the PNG** to see the screen, or `scripts/emu.sh dump` and Read the XML for the semantics tree (assert on text/content-desc instead of eyeballing pixels).

## Injecting states (debug builds only)

`scripts/emu.sh state <cmd> [am-broadcast extras]` drives `DebugStateReceiver` (app/src/debug/). Everything is in-memory; `state reset` or a process restart returns to real data.

| cmd | extras | effect |
|-----|--------|--------|
| `seed` | `--ei shares N --ei uses N` | N fake saved connections per tab (ids `debug-share-1…`, `debug-use-1…`) |
| `share-starting` / `share-started` / `share-failed` / `share-stopped` | `--es id debug-share-1` (+ `--es err SHARE_FAILED` / `--es url "…"`) | per-connection share states |
| `connect-connecting` / `connect-connected` | `--es id debug-use-1` | client-side states (id selects which saved item shows active) |
| `connect-failed` | `--es err CONNECT_FAILED --es id debug-use-1` | sets ConnectState.error (err = any UserError name, default GENERIC) |
| `connect-disconnected` | | back to idle |
| `reset` | | reload real persisted data |

Toggling a *seeded* share on in the UI calls the real `share()` FFI with a bogus identity and fails — useful for exercising the real error path.

## Conditions matrix

```bash
scripts/emu.sh dark on|off          # system dark mode
scripts/emu.sh locale ru            # per-app locale (ru, be, en); needs API 33+
scripts/emu.sh fontscale 1.3        # back to 1.0 when done
scripts/emu.sh rotate 1             # 0=portrait 1=landscape; back to 0 when done
scripts/emu.sh kill                 # process death (state singletons reset)
```

## Driving the UI

```bash
scripts/emu.sh tap 540 800          # coordinates from shot/dump (1080x2400 device)
scripts/emu.sh text 'https://spora.to/s/abc'
scripts/emu.sh key 4                # BACK; 66=ENTER, 111=ESC
scripts/emu.sh swipe 900 1200 100 1200 300   # pager swipe Share→Use
```

The VPN consent dialog is a system dialog — find its OK button via `dump`, then `tap`.

## Inspecting the invisible

```bash
scripts/emu.sh notif    # app's notifications incl. actions (Disconnect/Stop all)
scripts/emu.sh vpn      # tun0 interface + connectivity VPN state
scripts/emu.sh log 200  # app-process logcat tail
```

## Caveats

- **Config changes wipe seeded connections**: dark/locale/rotate recreate MainActivity, whose onCreate reloads connections from the real (empty) store — but NOT activeShares/startingIds/connect flags, which survive in the singletons. Re-run `state seed …` (and re-inject per-connection states) after any config change, or inject states *after* setting conditions.
- `locale en` returns to English (there is no "clear" — set a concrete tag).

- Default AVD is `Pixel_6_root` (API 36 google_apis; covers all Android 13+ behavior incl. POST_NOTIFICATIONS gating). The Pixel_*_API_33 AVDs are broken — their system image is no longer installed (`sdkmanager "system-images;android-33;google_apis;x86_64"` would restore it). One emulator at a time with this script.
- Do **not** `pm grant` POST_NOTIFICATIONS — its absence is part of what needs testing.
- First `install` after `boot` can take a minute (gradle daemon + dexing).
- `state …` requires the app process to be running (launch first).
- Real `connect()` needs a live share URL from a real peer; real `share()` needs relay reachability. For UI work, always prefer injected states.
