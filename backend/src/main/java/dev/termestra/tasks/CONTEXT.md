# Tasks

Tasks keeps the Workspace's human- and Agent-editable plan synchronized between
the filesystem and browser without silently overwriting concurrent changes.

## Language

**Tasks Document**:
A Workspace-local plan shared by the user and managed Agents.
_Avoid_: Dispatch queue, database task table

**Tasks Revision**:
The content-derived identity of a Tasks Document version used to detect a stale
write.
_Avoid_: File timestamp, database version

**Protocol Guide**:
A generated reminder that re-anchors managed Agents in Termestra's team
coordination language.
_Avoid_: Tasks Document, user documentation
