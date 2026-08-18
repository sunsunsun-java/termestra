# Team

Team coordinates user-visible CLI members and keeps every assignment, outcome,
and cancellation explainable across runtime failures.

## Language

**Orchestrator**:
The Workspace's coordinating Agent, responsible for user communication and for
creating or cancelling Dispatches.
_Avoid_: Manager process, persisted worker

**TeamMember**:
A persistent, user-visible Worker that can receive business assignments by
name.
_Avoid_: Subagent, hidden agent, process

**Worker**:
The role a TeamMember performs when accepting a Dispatch and returning a Report.
_Avoid_: Run, terminal, subagent

**Dispatch**:
A business assignment from the Orchestrator to one TeamMember, identified for
its whole lifecycle by one stable Dispatch ID.
_Avoid_: Job, workflow step, terminal message

**Delivery**:
The technical act of submitting a Dispatch body to the addressed TeamMember.
_Avoid_: Completion, execution, Dispatch

**Delivery Attempt**:
One bounded try to perform a Delivery, carrying evidence about whether input may
have reached the TeamMember.
_Avoid_: Dispatch retry, task run

**Uncertain Delivery**:
A Delivery for which Termestra cannot prove that no input reached the
TeamMember; it requires deliberate recovery rather than automatic retry.
_Avoid_: Failed Dispatch, queued Delivery

**Report**:
A TeamMember's durable outcome for a Dispatch.
_Avoid_: Terminal output, status update, last PTY line

**Status Update**:
A TeamMember's progress signal to the Orchestrator that does not complete a
Dispatch.
_Avoid_: Report, Agent status

**Idempotency Key**:
A caller-scoped key identifying one logical Dispatch request across repeated
admission attempts.
_Avoid_: Dispatch ID, attempt ID

**Team Scenario**:
A product-defined recipe that creates a visible roster for a common mode of
work without becoming a persistent workflow of its own.
_Avoid_: Workflow, template instance, hidden team
