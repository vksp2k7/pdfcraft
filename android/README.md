# PdfCraft for Android

This Android app packages PdfCraft's existing Rust/WebAssembly frontend in a native Android WebView. The PDF engine and interface run locally on the device; the shell uses Android's document picker to open PDFs and writes exports to `Downloads/PdfCraft`. The app does not upload documents or require an account. This is a WebAssembly-backed Android port, not a separately implemented Kotlin PDF engine.

## Build

Requirements: Rust with `wasm32-unknown-unknown`, Trunk, JDK 17+, and Android SDK Platform 35 plus Build Tools 35.0.0. Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to the SDK location.

From this directory, run:

```sh
./build-apk.sh
```

This builds the WASM frontend from the checked-out source and then creates a debug-signed installable APK at:

```text
dist/android/pdfcraft-android-0.9.0-debug.apk
```

If you already have the built frontend under `../dist/web`, package it without rebuilding with `./build-apk.sh --skip-web-build`. A debug APK is for local installation/testing; it is not a Play Store release or a production-signed artifact.

## Android behavior

- The app is offline-capable and bundles the complete web frontend in the APK.
- Open/import uses Android's system document picker; no broad storage permission is requested.
- WebAssembly downloads are streamed to `Downloads/PdfCraft` using Android's scoped `MediaStore` API.
- The asset loader serves bundled files from a local HTTPS origin so WebAssembly gets the correct MIME type.

The Android project is under the repository's MIT OR Apache-2.0 license. Third-party assets retain their original licenses; see the repository's `NOTICE` and `ATTRIBUTION.md`.
