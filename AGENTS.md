# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spora is an Android P2P VPN app built with Kotlin and Jetpack Compose. The core networking logic is implemented in Rust and exposed to Kotlin via UniFFI bindings with JNA.

Two modes of operation:
- **Server (Share)**: Shares your internet connection with a peer. Uses `ShareForegroundService` wrapping the Rust `share()` FFI call.
- **Client (Connect)**: Uses someone else's connection via a VPN tunnel. Uses `ConnectVpnService` (extends `VpnService`) which creates a TUN interface and passes its fd to the Rust `connect()` FFI call.

## Build Commands

```bash
./gradlew :app:assembleDebug          # Build debug APK
./gradlew :app:assembleRelease        # Build release APK
./gradlew :app:test                   # Run unit tests
./gradlew :app:connectedAndroidTest   # Run instrumented tests (requires device/emulator)
./gradlew lint                        # Run lint checks
./gradlew clean                       # Clean build
```

## Dev Feedback Loop (no manual testing needed)

`scripts/emu.sh` drives a headless emulator: boot, install, launch, screenshot, UI dump, input, dark mode/locale/font-scale switching, notification/VPN inspection. A debug-only `DebugStateReceiver` (`app/src/debug/`) lets adb put `ShareState`/`ConnectState` into any state (errors, active shares, connecting) without a relay or peer. Full recipes: `.claude/skills/emulator/SKILL.md`. Screenshots land in `.emu/` (gitignored) — read them to verify UI changes visually.

## Architecture

### Data Flow

```
UI (MainScreen tabs) <-> ShareState / ConnectState (singleton StateFlow) <-> Services <-> Rust FFI
```

### Server Mode

`ShareScreen` -> `ShareForegroundService` (regular foreground service) -> `uniffi.spora_ffi.share()` returns a share URL -> stored in `ShareState`

### Client Mode

`ConnectScreen` -> VPN permission prompt -> `ConnectVpnService` (extends `VpnService`) -> creates TUN interface -> `uniffi.spora_ffi.connect(url, tunFd)` -> state in `ConnectState`

The VPN uses a two-phase TUN establishment: first without routes (so STUN works), then with a full `0.0.0.0/0` route after the tunnel socket is protected via `VpnService.protect()`. The VPN's DNS server is the sharer's forwarder address (`dnsForwarderAddress()`), not a public resolver.

### State Management

`ShareState` and `ConnectState` are singleton objects with `StateFlow<*UiState>`. Each has states: idle/disconnected, starting/connecting, running/connected, error. Services mutate state directly; UI observes via `collectAsState()`.

### Rust/UniFFI Integration

- Auto-generated bindings: `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` (**do not edit manually**)
- Native libraries: `app/src/main/jniLibs/` (arm64-v8a, armeabi-v7a, x86, x86_64)
- Logging, the diagnostics service and network reporting are set up in `App.onCreate()` (see "Diagnostics" below)
- Key FFI functions:
  - `share(identityBytes: ByteArray, protector: SocketProtectorCallback?, connLogDir, connLogRetentionDays, connLogSessionsOnly, recordDir: String?): ShareResult` - starts sharing, returns handle + URL, throws `ShareException`
  - `connect(url: String, tunFd: Int, protector: SocketProtectorCallback, mtuCallback: MtuCallback?, recordDir: String?): Int` - connects to a peer, returns tunnel handle, throws `ConnectException`. Rust calls `protector.protect(fd)` for each socket that needs `VpnService.protect()`.
  - `recordDir` (both) turns on the core's connection record for that run; the app always passes `Diagnostics.recordDir()` (`noBackupFilesDir/records`)
  - `disconnect(handle: Int)` - tears down the tunnel identified by handle
  - `stopShare(handle: Int)` - stops the share session identified by handle
  - `makeIdentity(): ByteArray` - generates a fresh identity (cert + key + secret) as opaque serialized bytes. The app persists them (base64 in `SharedConnectionStore`) and passes them back to `share()` so the share URL stays stable across launches.
  - `dnsForwarderAddress(): String` - the tunnel's synthetic resolver address (`100.64.0.53`, spora-core `dns::PROXY_ADDR`). The client passes it to `Builder.addDnsServer`; the sharer's core answers queries to it from the sharer's own resolvers. A convention both ends are built to, nothing negotiated.
  - `setShareDnsServers(handle: Int, servers: List<String>)` - hands a share session this device's resolvers (`LinkProperties.getDnsServers()`), because Android has no resolv.conf for the core to read. `ShareForegroundService` calls it when a share starts and from a default-network `NetworkCallback` on every link change; an empty list means "unknown" and the core falls back to public resolvers. Known defect: with Private DNS in strict mode the system resolves over TLS while the forwarder sends plain UDP to the same servers.

## Diagnostics: log, records, crashes, telemetry, bug reports

The core's `spora-service` (via the FFI) keeps the diagnostics; the app wires
it up (`App.kt`, `Diagnostics.kt`). Collection is always on and local;
telemetry is only an upload switch over what is already on the device
(default off). There is no third-party SDK: Bugfender is gone (its
proprietary SDK blocked F-Droid publication).

- **Logging: use `SporaLog` (d/i/w/e), never `android.util.Log`.** It logs to
  logcat and, through `logLine`, into the core's diagnostic log file, where
  the app's lines sit between the core's, stamped with the running
  connection's record id (none while more than one record is open: a
  wrong id would be worse). A direct `Log.*` call is missing from bug reports
  and uploads. Share-link secrets are redacted on the way into the file.
  Lines logged before `initLogging` has run (the store resets in
  `MainActivity.onCreate`, a cold service start) wait in a bounded buffer
  (200, newest kept) and are written once the file opens, each with the
  time it was logged. Never log a saved connection's JSON or an exception
  that may quote it (org.json's do): the stores log the exception class only.
- `App.onCreate`: installs the uncaught-exception handler (`writeCrash`, then
  always the platform's handler, even if `writeCrash` throws), starts
  `Diagnostics.init` on `Dispatchers.IO` (`initLogging` + `initService`: they
  load the native library and touch files), and starts
  `UnderlyingNetworkReporter` alongside it (not after: a connect or share
  from a cold service start should find the network already reported). If
  `initService` fails, `Diagnostics.onForeground` (every `MainActivity.onStart`)
  retries it; until then `Diagnostics.unavailable` is true and the modal says
  so.
- Debug builds report `<versionName>-debug` (`versionNameSuffix`) as their app
  version and can be pointed at a test API with `-PsporaApi=<urls>` or
  `SPORA_API` (`BuildConfig.SPORA_API`, null when neither is set; split by
  `apiBases()` in Diagnostics.kt into `apiBases`), read as the CLI reads
  `SPORA_API`: comma-separated, trimmed, blanks dropped. Absent, they use
  the production API like release builds, which always do. Set but blank
  (`SPORA_API=`, `-PsporaApi=`, a stray comma) is NOT the default: the value
  is passed on as it is, `initService` refuses it as not a URL, and the
  modal shows telemetry as unavailable (the build itself does not fail: the
  debug block is configured for release tasks too). A dev build that will
  have its switch turned on is built with one: a local spora-web, or
  `http://127.0.0.1:9` (the device's own loopback, which answers nothing).
  With the switch off and no device registered, the app contacts no API at
  all.
- Everything lives under `noBackupFilesDir` (never backed up — a restored
  device token would clone a device): `diag/` (log + crash files, and an
  empty `diag-<pid>.lock` that the live process holds with `flock`; the
  next process's sweep deletes a dead pid's lock),
  `records/` (connection records, passed as `recordDir` to every
  `connect`/`share`), `service/` (`service.json`: the switch, device token,
  upload cursors). The sharer's connection log (`filesDir/connlog/`) is a
  different thing and never uploads.
- `UnderlyingNetworkReporter` tells the core which network is underneath any
  tunnel (`setNetworkState`: kind + validated, `NONE` when there is no
  non-VPN network with INTERNET). Not the default network — while our VPN is
  up, that IS the VPN: it tracks non-VPN INTERNET networks (Android 12+:
  best-matching callback; before: the default when it is one of them, else
  validated first, then ethernet > wifi > cellular). On 12+ the platform
  sends `onLost` for the best network before it re-ranks the rest, so a
  Wi-Fi loss with cellular up arrives as loss-then-cellular: `NONE` is
  reported only if nothing replaced the lost network within 2 s
  (`BestMatchTracker`).
- Upload switch: the diagnostics button in the header opens
  `DiagnosticsModalContent` (the "send telemetry automatically" switch, what
  is sent and why, device ID, last upload). The switch's state is
  `Diagnostics.status`; flipping it goes through
  `Diagnostics.setUploadEnabled` off the main thread, and a failure to
  change it is shown under the switch. When the account requires
  telemetry, switching off fails and the force notice
  (`TelemetryRequiredContent`, wording agreed with the core's
  `REQUIRED_NOTICE`) is shown; it is also shown once when
  `requiredNoticePending`, then `acknowledgeTelemetryNotice()`. `MainScreen`
  is the notice's one owner (the modal asks it through `onRequired`), so
  the two triggers can never stack two copies.
- Uploads use whatever network the device is on, mobile data included (a
  product decision: no metered-network deferral). With a client tunnel up
  they ride the tunnel; the core holds the uploader while the client is
  dormant (`setKeepalive(handle, 0)`, screen off — also when the screen went
  off while `connect` was still blocked; `ConnectVpnService` registers the
  screen receiver, reads `isInteractive` and sets the starting knob on the
  main thread, where the broadcasts and `disconnect()` arrive, so neither
  can interleave with it). With the switch off nothing is
  uploaded, but a device that was ever registered still checks its account's
  force flag about once a day (`GET /v1/devices/me`), which can turn
  uploads back on; the modal's copy says so.
- Bug reports: the feedback modal exports a zip (`exportBugReport`, records +
  log + crash files of the last days) to `cacheDir/reports/` and hands it to
  the share sheet with support@spora.to prefilled, through the FileProvider
  `${applicationId}.reports` (serves `cacheDir/reports` only; reports older
  than a day are deleted). No Spora server involved; if the export fails the
  message goes without it and the modal says so. The hand-off screen can
  open the chooser again (same text, same report) for a user who backed out
  of it.
- FFI calls that may touch files (`initLogging`, `initService`,
  `setTelemetryEnabled`, `acknowledgeTelemetryNotice`, `exportBugReport`)
  run off the main thread. `logLine` and `telemetryStatus` are cheap.

## Tech Stack

- **Kotlin**: 2.0.21, JVM target 11
- **Jetpack Compose**: BOM 2024.09.00 with Material 3
- **Target SDK**: 36 (min SDK 26)
- **JNA**: 5.18.1 (AAR artifact) for native interop
- **NDK**: 29.0.14206865

## Key Files

- `app/src/main/java/to/spora/android/MainActivity.kt` - UI: `MainScreen` with tab navigation, `ShareScreen`, `ConnectScreen`
- `app/src/main/java/to/spora/android/ShareForegroundService.kt` - Server mode foreground service
- `app/src/main/java/to/spora/android/ConnectVpnService.kt` - Client mode VPN service
- `app/src/main/java/to/spora/android/ShareState.kt` / `ConnectState.kt` - State management singletons
- `app/src/main/java/to/spora/android/App.kt` - process start: crash capture, diagnostics init, network reporting
- `app/src/main/java/to/spora/android/SporaLog.kt` - the app's logger (logcat + the core's diagnostic log)
- `app/src/main/java/to/spora/android/Diagnostics.kt` - log/record/service dirs, the upload switch's state
- `app/src/main/java/to/spora/android/UnderlyingNetwork.kt` - reports the non-VPN network underneath to the core
- `app/src/main/java/to/spora/android/BugReport.kt` - bug-report export and share-sheet hand-off
- `app/src/main/java/to/spora/android/ui/FeedbackModal.kt` / `ui/DiagnosticsModal.kt` - feedback (with report) and the diagnostics switch
- `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` - Auto-generated FFI bindings (do not edit)
- `gradle/libs.versions.toml` - Centralized dependency versions

## Internationalization (i18n)

All user-facing strings must be defined in Android string resources — never hardcoded in Kotlin. The app supports three locales:

- `app/src/main/res/values/strings.xml` — English (default)
- `app/src/main/res/values-ru/strings.xml` — Russian
- `app/src/main/res/values-be/strings.xml` — Belarusian

When adding or changing any UI label, you **must** update all three files. The brand name "SPORA" stays hardcoded.

**Conventions:**
- Naming: `<scope>_<descriptor>` (e.g. `share_action_title`, `notif_vpn_connected`)
- Compose: use `stringResource(R.string.…)`
- Services / non-composable lambdas: use `getString(R.string.…)` or `context.getString(R.string.…)`
- Parameterized strings: `%1$s` / `%1$d` placeholders
- Plurals: use `<plurals>` with `one`/`other` for English, `one`/`few`/`many`/`other` for Russian and Belarusian
- Escape apostrophes in XML: `Mom\'s Phone`

## Required Permissions

- `INTERNET` - Network access
- `ACCESS_NETWORK_STATE` - The default network's resolvers (share DNS forwarder) and the underlying network for connection records
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` - Background operation (both services are `foregroundServiceType="specialUse"`)
- `POST_NOTIFICATIONS` - Status notifications
- `BIND_VPN_SERVICE` - VPN tunnel creation (client mode, declared on service in manifest)
