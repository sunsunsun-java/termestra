# Terminal

Terminal presents a Run to browser viewers without owning the Run or its
process, and keeps restore-to-live output consistent under backpressure.

## Language

**Terminal Viewer**:
One browser client observing and controlling a Run through a paired connection.
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
cursor begins.
_Avoid_: Full terminal history, Run output buffer

**Terminal Mirror**:
The bounded headless screen projection from which Restore Snapshots are made.
_Avoid_: Transcript, PTY
