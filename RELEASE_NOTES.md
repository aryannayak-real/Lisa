Lisa 0.5.1 — Usability fixes

This release fixes the problems found on a real device: missing keyboards, no way to
switch between chat and agent, no chat history or settings, and no way back from the
terminal.

- **Message field keyboard:** the composer now brings up the keyboard even before a model
  is loaded. If you send without a model, Lisa tells you to load one instead of silently
  ignoring the tap.
- **Chat / Agent switch:** two clear chips above the composer. Switching is no longer
  hidden or blocked when the shell has not been set up yet.
- **Menu with history and settings:** tap ☰ for **New chat**, saved **Recent chats**, and
  **Settings**. Chats are saved in app-private storage and reload on launch.
- **Settings** includes the agent switch, Terminal, model picker, new chat, and model
  status.
- **Terminal back button:** a **BACK** key is now the first key in the control row, so you
  no longer have to close the whole app.
- **Terminal keyboard:** the terminal view is now focusable, so tapping it or opening
  Terminal reliably shows the soft keyboard.
- IME padding keeps the chat composer above the keyboard.

Install note: this APK is debug-signed by CI, so uninstall any older Lisa build once before
installing. Stable signing is still on the roadmap.
