# Interactive startup recordings

Each JSON file contains one UTF-8 string with captured terminal output. JSON
escaping preserves CR, LF, CSI and OSC sequences for readable diffs. These are
local startup observations, not CLI instructions to execute.

Paths, selected model names and local extension/skill configuration are replaced
with neutral text while preserving cell counts and cursor-control sequences.
Hermes stops before the timeout-triggered shutdown erases its usable prompt.
The other captures stop at either an input screen or a workspace trust screen.

Tests replay both the whole recording and individual code points to cover control
sequences crossing output boundaries. The prompt adapter retains only the visible
80 by 24 screen; Pi's initial identity banner may have scrolled away by the end.

`opencode-followup.json` is a minimized OpenCode 1.18.29 screen derived from a
live dispatch failure. The session page omits the homepage's `Ask anything...`
placeholder; the empty bordered composer remains available. History is removed, the composer
is moved into the 80 by 24 viewport, and directory/model names are neutralized.
`OpenCodeInputRecognitionTest` also replays startup followed by consecutive
dispatches, and checks drafts, busy input, focus, and fresh repaint evidence.
The same checks cover transparent themes by replacing bottom-border glyphs with
spaces, following OpenCode's `backgroundElement` alpha-dependent rendering.

`opencode-session-opencode.json` and `opencode-session-lucent-orng.json` capture
actual OpenCode 1.18.29 in an isolated temporary workspace, using its default and
transparent themes. A loopback-only mock model returns a text response and then
requests a harmless command, which is left at the permission dialog. No live
account, user workspace, or existing Worker is involved. Only fixture-owned
temporary paths appear; those synthetic paths are retained because incremental
rendering reuses unchanged cells (including a path's `r` in a later `interrupt`
hint). Replacing path text would corrupt that later frame. Output event boundaries are
preserved, including resize from 100x35 to 120x40; checkpoints cover the homepage
Unicode ellipsis, busy output, idle input, resize, and the real permission dialog.
These fixtures test native rendering; the separate shell/PTY test verifies the
transport, submission bytes, and process cleanup.
