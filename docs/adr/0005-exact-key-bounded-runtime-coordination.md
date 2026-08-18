# ADR-0005: Exact-key bounded runtime coordination

- Status: Accepted
- Date: 2026-08-12

## Context

Workspace lifecycle, Agent launch/removal, and PTY delivery must not race on the same runtime
resource. The earlier fixed-stripe locks could make unrelated Workspace IDs block one another, and
unbounded acquisition let an HTTP start request wait until the browser's 60-second deadline without
explaining which resource was busy. Long Orchestrator preparation also held the exclusive Workspace
lock even though only metadata initialization required exclusivity.

## Decision

Termestra coordinates runtime work with reference-counted locks keyed by the exact Workspace ID and
the exact `(workspace_id, agent_id)` pair. Ordinary Workspace and Agent operations share a fair,
reentrant Workspace read lock; lifecycle operations use its write lock; operations for the same Agent
also use a fair, reentrant Agent lock. One coordinator call has one acquisition deadline, currently two
seconds by default, shared across every lock it needs. The deadline bounds lock acquisition only and
does not cancel an operation after it has entered the protected section.

Failure to acquire within the deadline raises the typed `RuntimeOperationBusyException`. UI HTTP
commands expose it as retryable `409 RUNTIME_OPERATION_BUSY` rather than waiting for a generic client
timeout. Reentrant nesting is supported because application seams legitimately call other seams for
the same resource; a shared-to-exclusive Workspace upgrade is rejected immediately because it cannot
be performed safely. Registry entries are removed only after all owners and waiters release their
references, so the exact-key registry remains bounded without allowing two locks for one live key.

After Workspace registration, the existence check and metadata initialization run under the
exclusive lock; Orchestrator preparation then runs under a shared Workspace lock. This still excludes
deletion while a CLI is being prepared but does not freeze reads or unrelated Agent work. A Delivery
that already holds a SQLite claim but encounters runtime contention is durably deferred without
touching the PTY or consuming its delivery-attempt budget.

## Considered options

- Fixed lock stripes were rejected because hash collisions couple unrelated Workspaces and make
  latency depend on identity values rather than domain ownership.
- Unbounded waiting was rejected because HTTP deadlines then hide contention behind an ambiguous
  timeout whose outcome the client cannot classify.
- Holding one exclusive Workspace lock for complete Orchestrator preparation was rejected because a
  slow external CLI is not an exclusive metadata mutation.
- Treating coordinator contention as a Delivery failure was rejected because no input was attempted;
  spending retry budget or moving to `uncertain` would misrepresent the real delivery outcome.

## Consequences

Conflicting work now fails fast with a stable, retryable contract, while unrelated Workspaces and
different Agents in one Workspace can progress concurrently. Callers that claim durable work before
coordination must explicitly release or defer that claim on `RuntimeOperationBusyException`. The
coordinator is an in-process safety boundary, not a distributed lock and not an execution timeout.
