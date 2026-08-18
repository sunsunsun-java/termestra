# ADR-0006: Context ownership and lifecycle deletion

- Status: Accepted
- Date: 2026-08-18

Workspace owns registration and canonical path; Team owns TeamMembers,
Messages, Dispatches, and Deliveries; Agent Execution owns launch, Run, and
provider-session state; Configuration owns presets, templates, and app state.
Ordinary reads and writes stay behind the owning context's application
interfaces and repositories.

Workspace and Worker hard deletion are a deliberate exception. Because the
local modular monolith uses one SQLite database and a partially deleted runtime
graph is unsafe, the persistence adapter that initiates deletion removes all
related rows across those contexts in one SQLite transaction. Live processes,
watchers, credentials, and projections are cleaned only after that transaction
commits. This exception is limited to lifecycle destruction and does not create
a shared repository for normal behavior.

The alternative—one transaction per context with compensating deletion—was
rejected because a crash between transactions could leave a Run, Delivery, or
configuration reachable after its Workspace or TeamMember disappeared. This
record clarifies the context list in ADR-0001 and the deletion rule in the
current context map.
