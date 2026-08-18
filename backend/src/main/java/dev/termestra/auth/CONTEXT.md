# Auth

Auth protects the loopback runtime from cross-origin browser actions and keeps
managed Agent commands attributable for the lifetime of a process.

## Language

**UI Session**:
A process-scoped authorization for one local browser environment.
_Avoid_: User account, login, Provider Session

**Agent Credential**:
A process-scoped secret that attributes commands to one managed Agent Run.
_Avoid_: API key, user credential, UI Session

**Local-Only Runtime**:
The Termestra application boundary intentionally available only on the same
host machine.
_Avoid_: Multi-user authentication, network sandbox
