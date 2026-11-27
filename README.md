# Lisa

A small, local-first Android chat app. It runs GGUF language models on-device through
llama.cpp, with no server and no bundled weights: you pick a model file and Lisa keeps a
copy in app storage.

## Status

Early development (`0.3.0`). One model loaded at a time, CPU inference, ARM64.
Includes an embedded Termux-style terminal with `pkg`/`apt` support.

## What is here

- `app/src/main/cpp` — a small JNI bridge over llama.cpp (CMake + FetchContent).
- `app/src/main/kotlin/com/lisaai/inference` — native binding, engine, UTF-8 streaming,
  and ChatML prompt formatting.
- `app/src/main/kotlin/com/lisaai/ui` — Compose chat screen and state.
- `app/src/main/kotlin/com/lisaai/terminal` — bootstrap installer and terminal activity.
- `terminal-emulator/`, `terminal-view/` — Termux terminal modules (GPL-3.0-only).

## Terminal

The **Terminal** button downloads the Termux aarch64 bootstrap (~30 MB) on first use,
extracts it into app-private storage, and starts a real `bash`. The bootstrap paths are
repointed from `com.termux` to `com.lisaai` (both ten characters), which is why the
applicationId must not change.

`targetSdk` is pinned to 28 so Android allows executing binaries from the private data
directory. Lisa is sideload-only for this reason.

Package installation works through `pkg`/`apt`. Lisa installs an apt
`DPkg::Pre-Install-Pkgs` hook (`$PREFIX/bin/lisa-repoint-debs`) that unpacks each
downloaded `.deb`, renames the `com.termux` path components and rewrites the prefix
inside binaries, scripts, symlinks and maintainer metadata, then repacks it before
dpkg installs it.

This makes installs slower than in Termux (every package is unpacked and repacked) and
needs free temp space while a package is processed. Apt's package cache is cleared after
installs because the repacked files no longer match the original hashes.

## Build

Requirements: JDK 17+, Android SDK platform 35, NDK 27.0.12077973, CMake 3.22.1.

```
./gradlew :app:assembleDebug
```

The native library is fetched and built by CMake, so the first build needs network access.

## Use

1. Install the debug APK.
2. Tap **Load model** and pick a single-file `.gguf`.
3. Chat.

For this class of hardware, start with a ~0.6B parameter model at `Q4_0`/`Q4_K_M` and a
short context. Larger models are limited by memory bandwidth, not by the app.

## Roadmap

- Agent loop that can plan and act through the terminal.

## License

GPL-3.0-only. See `NOTICE` for vendored Termux components.
