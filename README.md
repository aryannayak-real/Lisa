# Lisa

A small, local-first Android chat app. It runs GGUF language models on-device through
llama.cpp, with no server and no bundled weights: you pick a model file and Lisa keeps a
copy in app storage.

## Status

Early development (`0.5.0`). One model loaded at a time, CPU inference, ARM64.
Includes an embedded Termux-style terminal with `pkg`/`apt` support, a control-key row,
and an approval-based agent mode that can run shell commands for the model.

## What is here

- `app/src/main/cpp` — a small JNI bridge over llama.cpp (CMake + FetchContent).
- `app/src/main/kotlin/com/lisaai/inference` — native binding, engine, UTF-8 streaming,
  ChatML prompt formatting, and the agent command protocol.
- `app/src/main/kotlin/com/lisaai/ui` — Compose chat screen and state.
- `app/src/main/kotlin/com/lisaai/terminal` — bootstrap installer, terminal activity,
  and the command executor used by agent mode.
- `terminal-emulator/`, `terminal-view/` — Termux terminal modules (GPL-3.0-only).

## Agent mode

Agent mode lets the model use the shell. Tap the **Agent** chip next to the composer
after loading a model. The first time, open **Terminal** once so the bootstrap is
installed.

1. Ask for something that needs the shell.
2. The model replies with one proposed command wrapped in `<lisa-run>…</lisa-run>`.
3. Lisa shows the exact command and waits. Tap **Run** or **Skip**.
4. On **Run**, the command executes with `bash -c` in the embedded `$PREFIX`, with home
   as the working directory. Stdout and stderr are captured.
5. The output is fed back to the model, which can propose the next command or give the
   final answer. Up to 6 commands per request.

Every command requires explicit approval; Lisa never runs a model proposal
automatically. Commands time out after 60 seconds, output is capped at 2,000 characters
(both for display and for the model), and older turns are dropped to keep the prompt
inside the 2,048-token context.

Agent mode is not a security sandbox. The model can be wrong, and an approved command
has the same access as the rest of the app: app-private storage only, no root. Read the
command before running it.

If the model ignores the marker, a fenced `sh`/`bash` block is also accepted as a
command proposal.

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

### Terminal controls

A key row sits above the keyboard:

- **Ctrl** and **Alt** toggle latches. With Ctrl on, typing `C` sends Ctrl+C, `D` sends
  Ctrl+D, and so on. Tap the key again to release it.
- **Esc**, **Tab**, arrows, Home, End, PgUp, PgDn, and Delete send the expected sequences.
- **Paste** inserts the clipboard; `-`, `/`, `|`, and `~` are one tap away.
- **A-/A+** change the font size (12–48) and remember it across sessions.

The terminal still has no shared-storage access and no background service, so long-running
jobs can be killed when the app is backgrounded.

## Build

Requirements: JDK 17+, Android SDK platform 35, NDK 27.0.12077973, CMake 3.22.1.

```
./gradlew :app:assembleDebug
```

The native library is fetched and built by CMake, so the first build needs network access.

## Use

1. Install the debug APK.
2. Tap **Load model** and pick a single-file `.gguf`, or download the default
   Qwen3.5 0.8B Q4_K_M model.
3. Chat. For shell work, open **Terminal** once, then enable **Agent**.

For this class of hardware, start with a ~0.6B parameter model at `Q4_0`/`Q4_K_M` and a
short context. Larger models are limited by memory bandwidth, not by the app.

## Known limits

- Releases are debug-signed by ephemeral CI runners, so installing a new version over an
  old one currently needs a one-time uninstall. Stable signing is planned.
- The terminal has no shared-storage access (`/sdcard`) and no background service, so
  long-running jobs can be killed when the app is backgrounded.
- Termux:API, Termux:X11, and widgets expect `com.termux` and do not work here.
- Some `pkg` packages may still misbehave after repointing; installs are slower.
- Small models are weak at planning. Agent mode works best for short, concrete tasks.

## Roadmap

- Stable signing so future APKs update over each other.
- Shared storage and a foreground service for long-running shell jobs.
- Model settings (context, threads, temperature, system prompt) and a model catalog.
- Richer agent loop (streamed command output, per-command timeouts, plan review).

## License

GPL-3.0-only. See `NOTICE` for vendored Termux components.
