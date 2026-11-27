Lisa 0.5.0 — Terminal Controls

The embedded terminal now has a control-key row, so you can actually drive the shell.

- **CTRL** and **ALT** toggle latches. Turn on Ctrl, type `C` on the keyboard, and the terminal receives Ctrl+C. Tap again to release.
- **ESC**, **TAB**, arrows, Home, End, PgUp, PgDn, and Delete send the correct escape sequences.
- **PASTE** inserts the clipboard; `-`, `/`, `|`, and `~` are one tap away.
- **A-/A+** change the font size from 12 to 48 and remember it across sessions.
- The key row stays above the soft keyboard, so Ctrl+C works mid-command.
- README updated with the new controls.

Open **Terminal** as before. Agent mode is unchanged and still requires approval for every command.

Install note: this APK is debug-signed by CI, so uninstall any older Lisa build once before installing. Stable signing is still on the roadmap.

SHA-256: `0ca2a715033bdee875eed74efde84f090fa0296fe427394d88261c11746519de`
