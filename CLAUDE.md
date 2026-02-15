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

## Architecture

### Data Flow

```
UI (MainScreen tabs) <-> ShareState / ConnectState (singleton StateFlow) <-> Services <-> Rust FFI
```

### Server Mode

`ShareScreen` -> `ShareForegroundService` (regular foreground service) -> `uniffi.spora_ffi.share()` returns a share URL -> stored in `ShareState`

### Client Mode

`ConnectScreen` -> VPN permission prompt -> `ConnectVpnService` (extends `VpnService`) -> creates TUN interface -> `uniffi.spora_ffi.connect(url, tunFd)` -> state in `ConnectState`

The VPN uses a two-phase TUN establishment: first without routes (so STUN works), then with a full `0.0.0.0/0` route after the tunnel socket is protected via `VpnService.protect()`.

### State Management

`ShareState` and `ConnectState` are singleton objects with `StateFlow<*UiState>`. Each has states: idle/disconnected, starting/connecting, running/connected, error. Services mutate state directly; UI observes via `collectAsState()`.

### Rust/UniFFI Integration

- Auto-generated bindings: `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` (**do not edit manually**)
- Native libraries: `app/src/main/jniLibs/` (arm64-v8a, armeabi-v7a, x86, x86_64)
- `initAndroidLogging()` is called once in `MainActivity.onCreate()`
- Key FFI functions:
  - `share(): String` - async, returns share URL, throws `ShareException`
  - `connect(url: String, tunFd: Int): Int` - async, returns tunnel handle, throws `ConnectException`
  - `disconnect(handle: Int)` - tears down the tunnel identified by handle
  - `getTunnelSocketFd(handle: Int): Int` - returns the UDP socket fd for `VpnService.protect()`

## Tech Stack

- **Kotlin**: 2.0.21, JVM target 11
- **Jetpack Compose**: BOM 2024.09.00 with Material 3
- **Target SDK**: 36 (min SDK 26)
- **JNA**: 5.18.1 (AAR artifact) for native interop
- **NDK**: 29.0.14206865

## Key Files

- `app/src/main/java/net/spora/android/MainActivity.kt` - UI: `MainScreen` with tab navigation, `ShareScreen`, `ConnectScreen`
- `app/src/main/java/net/spora/android/ShareForegroundService.kt` - Server mode foreground service
- `app/src/main/java/net/spora/android/ConnectVpnService.kt` - Client mode VPN service
- `app/src/main/java/net/spora/android/ShareState.kt` / `ConnectState.kt` - State management singletons
- `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` - Auto-generated FFI bindings (do not edit)
- `gradle/libs.versions.toml` - Centralized dependency versions

## Required Permissions

- `INTERNET` - Network access
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` - Background operation
- `POST_NOTIFICATIONS` - Status notifications
- `BIND_VPN_SERVICE` - VPN tunnel creation (client mode, declared on service in manifest)
