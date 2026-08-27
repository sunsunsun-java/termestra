# Configuration

Configuration owns reusable local defaults for launching Agents, describing
roles, and retaining small UI preferences.

## Language

**Command Preset**:
A named, reusable Agent CLI launch definition with command, arguments,
environment, availability, optional model argument capability, revision, and
optional recovery behavior.
_Avoid_: Launch Configuration, shell alias

**Role Template**:
A reusable role description and default launch choice from which a TeamMember
can be created.
_Avoid_: TeamMember, Team Scenario, marketplace entry

**Application State**:
A small local UI preference stored by key, such as the active Workspace.
_Avoid_: Domain state, browser cache, runtime registry
