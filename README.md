# Spora for Android

Android app for [Spora](https://github.com/atereshkin/spora), a P2P VPN: share your internet connection with a peer, or connect through someone else's. All networking lives in the Rust core; this repo is the Kotlin/Compose UI and the Android services around it.

## Build

The native library and its Kotlin bindings are built from the core, checked out next to this repo (as `spora/` beside `spora-android/`) at the ref pinned in [`.github/core-ref`](.github/core-ref):

```bash
git clone https://github.com/atereshkin/spora ../spora
git -C ../spora checkout "$(grep -v '^#' .github/core-ref | head -1)"
../spora/build-ffi.sh    # needs cargo-ndk and an Android NDK; see the script header
./gradlew :app:assembleDebug
```

## License

[GPL-3.0](LICENSE)
