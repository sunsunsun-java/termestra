# Workspace

Workspace identifies a trusted local project directory and anchors all
Termestra-owned coordination for that directory.

## Language

**Workspace**:
A persistent Termestra registration for one canonical local directory, with a
stable identity and user-facing name.
_Avoid_: Repository, project, folder record

**Workspace Path**:
The canonical absolute directory selected for a Workspace.
_Avoid_: Working directory string, repository URL

**Workspace Metadata**:
Termestra-owned coordination documents maintained alongside a Workspace.
_Avoid_: Runtime database, source files

**Workspace Registration**:
The durable admission process that claims a Workspace Path, preserves that
directory's current checkout, initializes Workspace Metadata, and then makes
the Workspace visible.
_Avoid_: Git checkout, project import

**Open Target**:
A supported local editor, terminal, file manager, or system action used to open
a Workspace Path.
_Avoid_: Workspace, Agent CLI
