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
- `initAndroidLogging()` is called once in `MainActivity.onCreate()`
- Key FFI functions:
  - `share(identityBytes: ByteArray, protector: SocketProtectorCallback?): ShareResult` - starts sharing, returns handle + URL, throws `ShareException`
  - `connect(url: String, tunFd: Int, protector: SocketProtectorCallback): Int` - connects to a peer, returns tunnel handle, throws `ConnectException`. Rust calls `protector.protect(fd)` for each socket that needs `VpnService.protect()`.
  - `disconnect(handle: Int)` - tears down the tunnel identified by handle
  - `stopShare(handle: Int)` - stops the share session identified by handle
  - `makeIdentity(): ByteArray` - generates a fresh identity (cert + key + secret) as opaque serialized bytes. The app persists them (base64 in `SharedConnectionStore`) and passes them back to `share()` so the share URL stays stable across launches.
  - `dnsForwarderAddress(): String` - the tunnel's synthetic resolver address (`100.64.0.53`, spora-core `dns::PROXY_ADDR`). The client passes it to `Builder.addDnsServer`; the sharer's core answers queries to it from the sharer's own resolvers. A convention both ends are built to, nothing negotiated.
  - `setShareDnsServers(handle: Int, servers: List<String>)` - hands a share session this device's resolvers (`LinkProperties.getDnsServers()`), because Android has no resolv.conf for the core to read. `ShareForegroundService` calls it when a share starts and from a default-network `NetworkCallback` on every link change; an empty list means "unknown" and the core falls back to public resolvers. Known defect: with Private DNS in strict mode the system resolves over TLS while the forwarder sends plain UDP to the same servers.

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
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` - Background operation
- `POST_NOTIFICATIONS` - Status notifications
- `BIND_VPN_SERVICE` - VPN tunnel creation (client mode, declared on service in manifest)
