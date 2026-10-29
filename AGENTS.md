# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spora is an Android P2P VPN app built with Kotlin and Jetpack Compose. The core networking logic is implemented in Rust and exposed to Kotlin via UniFFI bindings with JNA.

Two modes of operation:
- **Server (Share)**: Shares your internet connection with a peer. Uses `ShareForegroundService` wrapping the Rust `startShare()` FFI call.
- **Client (Connect)**: Uses someone else's connection via a VPN tunnel. Uses `ConnectVpnService` (extends `VpnService`) which creates a TUN interface and passes its fd to the Rust `connect()` FFI call.

## Build Commands

```bash
git submodule update --init           # the Rust core, at core/
scripts/build-native.sh --install-toolchain  # pinned Rust, Android targets, cargo-ndk, NDK
scripts/build-native.sh               # dev native libs (debug) + regenerated bindings
./gradlew :app:assembleDebug          # Build debug APK
./gradlew :app:assembleRelease        # Build release APK (builds the native libs from core/ itself)
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

`ShareScreen` -> `ShareMaker` (`makeShare()` on the relays the settings name: a share blob, stored in `SharedConnectionStore`) -> `ShareForegroundService` (regular foreground service) -> `startShare(blob)` returns the share's URL -> stored in `ShareState`

### Relays and shares

A share is the core's: one immutable blob per link (share id, secret, its relays as (endpoint, credential) pairs), made under the device identity the core's service keeps, its URL a pure function of it. Changing relays therefore means a new link; settings apply to links made from then on. Design: `../spora-kb/relay-management-decisions.md` (B3, S2–S4).

- **Settings** (the header's sliders icon) is a list of sections, each its own page: Relays (`ui/RelaySettingsScreen.kt`) and Telemetry (`ui/TelemetrySettingsScreen.kt`, the upload switch, see Diagnostics). `SettingsPages` (`ui/SettingsScreen.kt`) keeps them as a stack, so OPEN SETTINGS under a share error opens Relays alone and back returns to the share. Each page is a `FullScreenPage` (Components.kt), drawn in the activity's window because a Compose dialog window gets no system-bar insets here.
- **Relays**: Spora's relays on/off (`RelaySettings.useSpora`, default on) and the user's own relays (`OwnRelay`: endpoint in `?r=` form, checked by `relayEndpointError` as the core parses it; an optional token, sent as `RelayCredential.Static` UTF-8 bytes, else `None`). Independent: a share takes Spora's advised relays first (credential `Spora`), then the own ones. Neither means no link can be made (`UserError.NO_RELAYS`). Stored encrypted (`relay_settings`), tokens included.
- **Relay access** (`RelayAccess`): the service fetches advice + relay token (`refreshRelays`, registering the device) and renews it in the background while `ServiceConfig.manageRelays`, which the app sets when Spora's relays are on or a saved share uses them (there is no FFI to change it later; `startShare` of a share on Spora's relays turns it on). `ShareMaker` fetches first only when Spora's relays are wanted and none are cached. The Relays page polls `relayAccessStatus()` for its status line.
- **State key**: `initService` gets `StateKey.Provided` from the encrypted store (`service_key`), or `Mint` on a first run / after the store was lost, and the minted key is saved (`commit`). A key that does not open `service.json` is a fresh device: saved shares then fail with `ShareException.IdentityMismatch` (`UserError.SHARE_LINK_LOST`) and their card offers MAKE A NEW LINK (a new share under the same name and id, sent again).
- **Encrypted stores** all go through `SecurePrefs.open` (one lock: concurrent first opens from the activity and the service's IO start could each create the Keystore master key).
- The connection log is per share: `filesDir/connlog/<share id>` (the core binds its database to the share's routing hash).

### Client Mode

`ConnectScreen` -> VPN permission prompt -> `ConnectVpnService` (extends `VpnService`) -> creates TUN interface -> `uniffi.spora_ffi.connect(url, tunFd)` -> state in `ConnectState`

The VPN uses a two-phase TUN establishment: first without routes (so STUN works), then with a full `0.0.0.0/0` route after the tunnel socket is protected via `VpnService.protect()`. The VPN's DNS server is the sharer's forwarder address (`dnsForwarderAddress()`), not a public resolver.

### State Management

`ShareState` and `ConnectState` are singleton objects with `StateFlow<*UiState>`. Each has states: idle/disconnected, starting/connecting, running/connected, error. Services mutate state directly; UI observes via `collectAsState()`.

### Rust/UniFFI Integration

- Auto-generated bindings: `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` (**do not edit manually**)
- The core is the `core/` submodule (github.com/Spora-VPN/spora, HTTPS URL so anonymous clones work); its recorded commit is the core the app is built against, and the committed bindings must match it (CI regenerates them and fails on a diff). Bumping the core: check out the new commit in `core/`, run `scripts/build-native.sh`, commit `core` together with `app/src/main/java/uniffi`. Core work can happen in a sibling checkout: `scripts/build-native.sh --core ../spora` (or `../spora/build-ffi.sh`) builds dev libs from it.
- Native libraries (arm64-v8a, armeabi-v7a, x86, x86_64): debug APKs package the dev libs in the gitignored `app/src/main/jniLibs/` (despite the path, debug only: `main`'s jniLibs are switched off in `app/build.gradle.kts`). Release APKs package only what Gradle's `buildNativeRelease` task builds from `core/` with `scripts/build-native.sh --release`, so a stale dev library can never ship.
- Logging, the diagnostics service and network reporting are set up in `App.onCreate()` (see "Diagnostics" below)
- Key FFI functions:
  - `makeShare(own: List<RelayEntry>, useSpora: Boolean): ByteArray` - a new share blob, offline: with `useSpora` the cached advice first (`ShareException.NoRelayAdvice` without one: `refreshRelays()` first), then `own`. `shareUrl` / `shareId` / `shareRelays(blob)` are pure accessors.
  - `startShare(shareBlob, protector: SocketProtectorCallback?, connLogDir, connLogRetentionDays, connLogSessionsOnly, recordDir: String?, stunServers): ShareResult` - starts sharing, returns handle + URL, throws `ShareException` (`NotInitialized` before `initService`, `IdentityMismatch`, `AlreadyRunning`). No network of its own: Spora's relays get the cached relay token and every renewal.
  - `refreshRelays(): RelayAccessStatus` (network, off the main thread) / `relayAccessStatus()` (cheap) - the device's access to Spora's relays.
  - `connect(url: String, tunFd: Int, protector: SocketProtectorCallback, mtuCallback: MtuCallback?, recordDir: String?): Int` - connects to a peer, returns tunnel handle, throws `ConnectException`. Rust calls `protector.protect(fd)` for each socket that needs `VpnService.protect()`.
  - `recordDir` (both) turns on the core's connection record for that run; the app always passes `Diagnostics.recordDir()` (`noBackupFilesDir/records`)
  - `disconnect(handle: Int)` - tears down the tunnel identified by handle
  - `stopShare(handle: Int)` - stops the share session identified by handle
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
  retries it; until then `Diagnostics.unavailable` is true and the Telemetry
  settings page says so.
- Debug builds report `<versionName>-debug` (`versionNameSuffix`) as their app
  version and can be pointed at a test API with `-PsporaApi=<urls>` or
  `SPORA_API` (`BuildConfig.SPORA_API`, null when neither is set; split by
  `apiBases()` in Diagnostics.kt into `apiBases`), read as the CLI reads
  `SPORA_API`: comma-separated, trimmed, blanks dropped. Absent, they use
  the production API like release builds, which always do. Set but blank
  (`SPORA_API=`, `-PsporaApi=`, a stray comma) is NOT the default: the value
  is passed on as it is, `initService` refuses it as not a URL, and the
  Telemetry page shows it as unavailable (the build itself does not fail: the
  debug block is configured for release tasks too). A dev build that will
  have its switch turned on is built with one: a local spora-web, or
  `http://127.0.0.1:9` (the device's own loopback, which answers nothing).
  Spora's relays (on by default) register the device with the API at the
  first start and keep its relay token fresh. With them off, no saved share
  on them, the switch off and no device registered, the app contacts no API
  at all.
- Everything lives under `noBackupFilesDir` (never backed up — a restored
  device token would clone a device): `diag/` (log + crash files, and an
  empty `diag-<pid>.lock` that the live process holds with `flock`; the
  next process's sweep deletes a dead pid's lock),
  `records/` (connection records, passed as `recordDir` to every
  `connect`/`startShare`), `service/` (`service.json`: the device identity,
  the switch, device token, upload cursors, relay advice and token; the
  secrets sealed with the state key, see "Relays and shares"). The sharer's connection log (`filesDir/connlog/`) is a
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
- Upload switch: Settings › Telemetry (`TelemetrySettingsContent`: what is
  sent and why, the "send telemetry automatically" switch, device ID, last
  upload). The switch's state is
  `Diagnostics.status`; flipping it goes through
  `Diagnostics.setUploadEnabled` off the main thread, and a failure to
  change it is shown under the switch. When the account requires
  telemetry, switching off fails and the force notice
  (`TelemetryRequiredContent`, wording agreed with the core's
  `REQUIRED_NOTICE`) is shown; it is also shown once when
  `requiredNoticePending`, then `acknowledgeTelemetryNotice()`. `MainScreen`
  is the notice's one owner (the page asks it through `onRequired`), so
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
  uploads back on; the page's copy says so.
- Bug reports: the feedback modal exports a zip (`exportBugReport`, records +
  log + crash files of the last days) to `cacheDir/reports/` and hands it to
  the share sheet with support@spora.to prefilled, through the FileProvider
  `${applicationId}.reports` (serves `cacheDir/reports` only; reports older
  than a day are deleted). No Spora server involved; if the export fails the
  message goes without it and the modal says so. The hand-off screen can
  open the chooser again (same text, same report) for a user who backed out
  of it.
- FFI calls that may touch files or the network (`initLogging`,
  `initService`, `refreshRelays`, `makeShare`, `startShare`,
  `setTelemetryEnabled`, `acknowledgeTelemetryNotice`, `exportBugReport`)
  run off the main thread. `logLine`, `telemetryStatus` and
  `relayAccessStatus` are cheap.

## Reproducible Releases (F-Droid)

F-Droid rebuilds each release from its tag and publishes our signed APK only if its rebuild is byte-identical (recipe: `AllowedAPKSigningKeys` = the release cert, `binary` = the APK on the tag's GitHub release, which `scripts/release.sh` offers to create). What keeps a release reproducible:

- `scripts/build-native.sh` pins Rust (`RUST_VERSION`) and cargo-ndk (`CARGO_NDK_VERSION`); the NDK is `ndkVersion` and the API level is `minSdk` from `app/build.gradle.kts`. Changing any of them changes the output: change the recipe's `ndk:` line (and anything else it pins) in the same release.
- Release native builds remap the build machine's paths (`--remap-path-prefix` for `core/`, `CARGO_HOME`, the toolchain's rust-src), set the core's build stamp from the submodule commit, use `--locked`, and ignore RUSTFLAGS/wrappers/`CARGO_PROFILE_*` from the environment. They refuse a dirty `core/`, or one not at the commit HEAD records.
- Releases are built from a clean tree at the tagged commit, with JDK 21 (F-Droid's build server runs Debian trixie's OpenJDK 21), and signed by Gradle's signingConfig. Do not re-sign with `apksigner` from build-tools 35+: its padding breaks F-Droid's signature copy.
- `dependenciesInfo` is off (no Google-encrypted block in the signing block); `abiFilters` keeps JNA's dead ABIs out.
- Store listing text, icon, screenshots and per-versionCode changelogs live in `fastlane/metadata/android/{en-US,ru,be}/`.

## Tech Stack

- **Kotlin**: 2.0.21, JVM target 11
- **Jetpack Compose**: BOM 2024.09.00 with Material 3
- **Target SDK**: 36 (min SDK 26)
- **JNA**: 5.18.1 (AAR artifact) for native interop
- **NDK**: 29.0.14206865 (`ndkVersion`; the native build uses the same one)
- **Rust**: 1.97.1, cargo-ndk 4.1.2 (pinned in `scripts/build-native.sh`)

## Key Files

- `app/src/main/java/to/spora/android/MainActivity.kt` - UI: `MainScreen` with tab navigation, `ShareScreen`, `ConnectScreen`
- `app/src/main/java/to/spora/android/ShareForegroundService.kt` - Server mode foreground service
- `app/src/main/java/to/spora/android/ConnectVpnService.kt` - Client mode VPN service
- `app/src/main/java/to/spora/android/ShareState.kt` / `ConnectState.kt` - State management singletons
- `app/src/main/java/to/spora/android/App.kt` - process start: crash capture, diagnostics init, network reporting
- `app/src/main/java/to/spora/android/SporaLog.kt` - the app's logger (logcat + the core's diagnostic log)
- `app/src/main/java/to/spora/android/Diagnostics.kt` - log/record/service dirs, the service's start (state key, relay access), the upload switch's state
- `app/src/main/java/to/spora/android/Relays.kt` - relay settings (Spora's on/off, own relays), relay access status, relay-address checks
- `app/src/main/java/to/spora/android/ShareMaker.kt` - makes new shares on the relays the settings name
- `app/src/main/java/to/spora/android/SharedConnectionStore.kt` / `SecurePrefs.kt` - saved shares (blobs) in encrypted prefs, and the one lock every encrypted store opens under
- `app/src/main/java/to/spora/android/UnderlyingNetwork.kt` - reports the non-VPN network underneath to the core
- `app/src/main/java/to/spora/android/BugReport.kt` - bug-report export and share-sheet hand-off
- `app/src/main/java/to/spora/android/ui/FeedbackModal.kt` - feedback (with report)
- `app/src/main/java/to/spora/android/ui/SettingsScreen.kt` - settings: the list of sections and their page stack
- `app/src/main/java/to/spora/android/ui/RelaySettingsScreen.kt` / `ui/TelemetrySettingsScreen.kt` - the sections: the relays new links are made with, and the upload switch
- `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` - Auto-generated FFI bindings (do not edit)
- `gradle/libs.versions.toml` - Centralized dependency versions
- `scripts/build-native.sh` - builds the native library from `core/` (dev and release modes, toolchain pins, reproducibility flags)
- `scripts/release.sh` - the signed-release ceremony (checks, build, cert check, tag, draft GitHub release)

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
