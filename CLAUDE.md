# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spora is an Android P2P VPN app built with Kotlin and Jetpack Compose. The core sharing logic is implemented in Rust and exposed to Kotlin via UniFFI bindings with JNA.
The app can work in two modes: server (sharing your internet connection with a peer) and client (using someone else's connection).
The server mode is launched with the `share` FFI function, while the client mode is launched with `connect`. 

## Build Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Build release APK
./gradlew :app:assembleRelease

# Run unit tests
./gradlew :app:test

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest

# Run lint checks
./gradlew lint

# Clean build
./gradlew clean
```

## Architecture

### Core Components

1. **MainActivity** - Compose UI entry point with `ShareScreen()` composable that displays status, share URL, and start/stop controls

2. **ShareForegroundService** - Android foreground service that:
   - Calls the Rust `share()` function on IO dispatcher
   - Manages notification with status updates and stop action
   - Required for background operation on modern Android

3. **ShareState** - Singleton state management using `StateFlow<ShareUiState>` with states: idle, starting, running (with URL), error

### Rust/UniFFI Integration

- Auto-generated bindings in `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt`
- Native libraries in `app/src/main/jniLibs/` for arm64-v8a, armeabi-v7a, x86, x86_64
- Key functions:
  - `share(): String` - async, returns share URL, throws `ShareException`
  - `connect(url: String, tunFd: Int)` - async, throws `ConnectException`

### Data Flow

```
UI (ShareScreen) <-> ShareState (StateFlow) <-> ShareForegroundService <-> Rust FFI
```

## Tech Stack

- **Kotlin**: 2.0.21
- **Jetpack Compose**: BOM 2024.09.00 with Material 3
- **Target SDK**: 36 (min SDK 26)
- **JNA**: 5.18.1 for native interop
- **NDK**: 29.0.14206865

## Key Files

- `app/src/main/java/net/spora/android/MainActivity.kt` - UI entry point
- `app/src/main/java/net/spora/android/ShareForegroundService.kt` - Background service
- `app/src/main/java/net/spora/android/ShareState.kt` - State management
- `app/src/main/java/uniffi/spora_ffi/spora_ffi.kt` - Auto-generated FFI bindings (do not edit manually)
- `gradle/libs.versions.toml` - Centralized dependency versions

## Required Permissions

- `INTERNET` - Network access
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` - Background operation
- `POST_NOTIFICATIONS` - Status notifications
