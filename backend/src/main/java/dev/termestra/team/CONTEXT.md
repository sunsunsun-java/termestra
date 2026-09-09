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
name. It is idle or working only after its Run completes startup; a Run still
starting does not establish an available TeamMember. Saving a TeamMember and
accepting its process-start request do not imply startup has completed; startup
failure preserves the saved member for inspection, explicit retry, or deletion.
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
have reached the TeamMember. Deferring because the Agent is still starting or
visibly generating does not consume the failure retry budget and does not authorize
input before readiness.
_Avoid_: Dispatch retry, task run

**Uncertain Delivery**:
A Delivery for which Termestra cannot prove that no input reached the
TeamMember; it requires deliberate recovery rather than automatic retry.
_Avoid_: Failed Dispatch, queued Delivery

**Report**:
A TeamMember's durable outcome for a Dispatch. Acceptance includes one durable
notification to its Orchestrator; terminal notification is asynchronous and does
not change the accepted outcome. An identical explicit-Dispatch replay retains
the same report and notification. Failed or uncertain notifications are visible
recovery issues; retry never reopens the Dispatch, and uncertain retry requires
explicit confirmation that the Orchestrator may receive a duplicate.
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
