# Lisa

A small, local-first Android chat app. It runs GGUF language models on-device through
llama.cpp, with no server and no bundled weights: you pick a model file and Lisa keeps a
copy in app storage.

## Status

Early scaffold (`0.1.0`). One model loaded at a time, CPU inference, ARM64.

## What is here

- `app/src/main/cpp` — a small JNI bridge over llama.cpp (CMake + FetchContent).
- `app/src/main/kotlin/com/lisaai/inference` — native binding, engine, UTF-8 streaming,
  and ChatML prompt formatting.
- `app/src/main/kotlin/com/lisaai/ui` — Compose chat screen and state.

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

- Embedded terminal (Termux-style) so the assistant can run local commands.
- Agent loop that can plan and act through that terminal.

## License

GPL-3.0-only. The embedded terminal stage will vendor GPL-3.0 Termux modules, so the app is
GPL-3.0 from the start.
