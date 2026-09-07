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
