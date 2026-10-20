# Spora for Android

Android app for [Spora](https://github.com/Spora-VPN/spora), a P2P VPN: share your internet connection with a peer, or connect through someone else's. All networking lives in the Rust core; this repo is the Kotlin/Compose UI and the Android services around it.

## Build

The core is a git submodule at `core/`. The native library is built from it with a pinned Rust toolchain, cargo-ndk and NDK ([`scripts/build-native.sh`](scripts/build-native.sh) has the versions):

```bash
git clone --recurse-submodules https://github.com/Spora-VPN/spora-android
cd spora-android
scripts/build-native.sh --install-toolchain   # needs rustup and an Android SDK
scripts/build-native.sh                       # dev build of the native library
./gradlew :app:assembleDebug
```

`./gradlew :app:assembleRelease` builds the native library for all four ABIs from `core/` itself, and produces an unsigned APK unless a signing key is configured.

## Reproducible builds

Release builds are reproducible: building a release tag with `./gradlew :app:assembleRelease` (JDK 21) gives the same APK as the published one, apart from its signature. The native build removes the build machine's paths, takes its version stamp from the submodule commit, and pins every tool. F-Droid checks each release this way before publishing it.

To check a release yourself, compare your unsigned build with the published APK using [apksigcopier](https://github.com/obfusk/apksigcopier):

```bash
apksigcopier compare spora-<version>.apk --unsigned app/build/outputs/apk/release/spora-<version>.apk
```

## License

[GPL-3.0](LICENSE)
