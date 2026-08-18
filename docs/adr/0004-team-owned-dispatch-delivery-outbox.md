# ADR-0004: Team owns the Dispatch delivery outbox

- Status: Accepted
- Date: 2026-08-11

Termestra persists each Dispatch, its Message, and one `dispatch_deliveries` row in the same SQLite transaction. The Delivery row is both the Team-owned transactional outbox and the authority for PTY delivery attempts; a bounded background runtime claims it after commit. We deliberately reject synchronous request-thread delivery, a generic cross-context event-bus table, and automatic replay of uncertain PTY writes: the first loses work across crashes, the second duplicates a local domain-specific lifecycle, and the third can execute a task twice. Public Dispatch and Agent states remain unchanged.
