Lisa 0.4.0 — Agent Mode

Lisa can now use its embedded shell with your approval.

- New **Agent** chip next to the composer.
- The model proposes one command at a time using `<lisa-run>…</lisa-run>` (fenced `sh`/`bash` blocks are accepted as a fallback).
- The exact command is shown in an approval card with **Run** and **Skip**. Nothing runs automatically.
- On approval it runs with `bash -c` in the embedded `$PREFIX`, with home as the working directory; stdout and stderr are captured.
- Output is fed back to the model for up to 6 commands per request, with a 60-second timeout and a 2,000-character output cap.
- Prompts are trimmed to fit the 2,048-token context.
- README updated with setup, safety notes, and limits.

Open **Terminal** once to install the bootstrap, then load a model and enable Agent. Works best with short, concrete tasks on the 0.8B model.

Install note: this APK is debug-signed by CI, so uninstall any older Lisa build once before installing. Stable signing is still on the roadmap.
