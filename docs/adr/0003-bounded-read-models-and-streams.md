# ADR-0003: Bounded read models and streams

- Status: Accepted
- Date: 2026-08-07

## Context

Termestra originally reused the terminal run detail model for a UI polling endpoint. The endpoint was
queried every 500 ms and therefore serialized each active run's retained terminal output on every
request. A long-running page repeatedly allocated large JSON strings until the Chrome renderer ran
out of memory.

The problem was not the polling interval alone. A summary query had been allowed to depend on a
detail object containing an unbounded field. Mapping away that field in the browser was too late:
the server had already copied and serialized it and the browser had already parsed it.

## Decision

Termestra uses three distinct read contracts:

1. **Summary** — bounded identity and state used by lists, badges, navigation, and polling.
2. **Detail** — a single resource loaded on explicit demand; large fields are permitted only with a
   documented maximum.
3. **Stream** — incremental runtime output with an initial snapshot, atomic handoff, backpressure,
   and disconnect cleanup.

The following rules are mandatory:

- A list or polling endpoint never returns terminal output, document bodies, prompt history, logs,
  message history, or another field whose size grows with runtime duration.
- Hot query code consumes a dedicated projection. It may not materialize a detail view and then
  discard unwanted fields.
- Public response DTOs enumerate their fields explicitly. Collection endpoints have a limit,
  pagination, or a product-level cardinality bound.
- Derived UI data such as a terminal's last visible line is maintained incrementally and stored with
  a fixed maximum; it is never recomputed by replaying the complete history in a polling request.
- Every buffer, cache, retained completed run, and streaming queue has an explicit bound and cleanup
  lifecycle. Snapshot-to-stream transitions cannot lose or duplicate events.
- Service workers do not cache API or WebSocket traffic.

## Verification

High-frequency endpoint tests must cross the real HTTP boundary and verify both:

- the exact set of JSON fields; and
- a response-size budget that stays constant when the corresponding detail history grows.

Streaming integration tests cover snapshot ordering, slow consumers, reconnect replacement, exit
notification, and cleanup after the final viewer disconnects.

## Consequences

Summary DTOs and projections introduce a little duplication, but they make payload cost visible and
prevent accidental coupling to expanding runtime state. Detail access remains available when the
user opens a specific resource, while streams carry ongoing output without repeated full snapshots.
