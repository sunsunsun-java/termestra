# Agent Execution

Agent Execution turns a configured Workspace Agent into a supervised real CLI
process and records the durable lifecycle evidence needed for recovery.

## Language

**Agent**:
A configured Termestra actor associated with a Workspace and eligible to own
Runs.
_Avoid_: Run, TeamMember, model call

**Launch Configuration**:
The durable command, arguments, environment, preset association, and recovery
metadata used to start an Agent.
_Avoid_: Run, shell command string

**Run**:
One supervised lifetime of an Agent's local CLI process, identified by a stable
Run ID.
_Avoid_: Agent, Dispatch, terminal viewer

**Provider Session**:
The CLI provider's own resumable conversation identity captured from a Run.
_Avoid_: UI session, Run ID, terminal history

**Recovery Summary**:
A bounded, durable briefing assembled when a provider-native session cannot be
resumed.
_Avoid_: Full transcript, Provider Session

**Automatic Input**:
Termestra-authored input submitted to an Agent after prompt readiness is
observed, such as startup guidance or a Dispatch.
_Avoid_: Browser keystroke, terminal output
