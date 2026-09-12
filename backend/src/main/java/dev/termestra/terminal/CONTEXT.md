# Terminal

Terminal presents a Run to browser viewers without owning the Run or its
process, and keeps restore-to-live output consistent under backpressure.

## Language

**Terminal Viewer**:
One browser client observing and controlling a Run through a paired connection,
including a Run waiting for startup confirmation or retained startup-failure evidence.
_Avoid_: Run, Agent, PTY process

**IO Channel**:
The Terminal Viewer channel carrying text output to the browser and raw input
back to the Run.
_Avoid_: Control Channel, terminal session

**Control Channel**:
The Terminal Viewer channel carrying restore, resize, acknowledgement, stop,
error, and exit protocol messages.
_Avoid_: IO Channel, HTTP command

**Restore Snapshot**:
A bounded screen image handed to a Terminal Viewer before its live output
cursor begins. It also preserves an unfinished escape/CSI/OSC parser state so
the next live chunk continues the same sequence. OSC contents are not replayed
as clipboard or window-title side effects.
_Avoid_: Full terminal history, Run output buffer

**Terminal Mirror**:
The bounded headless screen projection from which Restore Snapshots are made.
Screen rendering does not own the Agent's startup-readiness decision.
Height changes preserve rows around the cursor and bounded scrollback. Combining
marks occupy their base cell; a cell retains at most 32 UTF-16 characters to bound
hostile combining runs.
_Avoid_: Transcript, PTY

## Exit handoff

Once Execution reports a terminal Run, its Viewer drains pending IO output and
waits for every emitted byte's rendering acknowledgement before sending `exit`
on Control. The two WebSockets do not otherwise share transport ordering. Even
one final unacknowledged byte uses the existing 30-second grace; timeout or
disconnect fails the drain and releases the Viewer. An empty output window needs
no acknowledgement. This only governs Viewer delivery, not retained Run output.
