# ADR-0001: Architecture baseline

- Status: Accepted; context ownership and lifecycle deletion clarified by ADR-0006
- Date: 2026-08-06

## Context

Termestra coordinates real local CLI Agents through a browser-based workbench.
Its Java runtime must remain maintainable as PTY lifecycle, WebSocket flow
control, SQLite recovery, task files, and CLI-provider behavior evolve
independently. Termestra's own public contracts and accepted decisions define
the product boundary.

## Decision

Termestra uses:

- Java 21+ and Maven;
- domain-driven design with explicit bounded contexts;
- hexagonal architecture inside every bounded context;
- lightweight CQRS: commands use aggregates and transactions, queries use context-owned projections;
- SQLite as the authority for persisted state;
- after-commit domain events and a SQLite outbox where delivery must survive restart;
- Spring WebFlux and Reactor Netty only in inbound transport adapters;
- SQLite JDBC, pty4j, filesystem watchers, and terminal emulation only in outbound adapters;
- constructor injection and a single composition root in `backend`'s `dev.termestra.bootstrap` package.

## Bounded contexts

1. Workspace: workspace identity, path, and registration.
2. Team: dispatch, report, status, cancellation, and pending work.
3. Agent Execution: launch configuration, live runs, exit, session capture, and recovery.
4. Terminal: PTY streams, viewer sessions, restore snapshots, resize, and backpressure.
5. Tasks: `.termestra/tasks.md` synchronization and `.termestra/PROTOCOL.md` recovery guidance.
6. Configuration: settings, command presets, and role templates.
7. Marketplace: bundled role catalog and import behavior.
8. Auth: local UI sessions and per-agent credentials.

## Dependency rules

- Domain packages do not depend on frameworks or infrastructure.
- Application packages depend only on their own domain and the minimal shared kernel.
- Output ports are owned by the application code that consumes them.
- Adapters implement ports; application code never imports adapter classes.
- Bounded contexts never share repositories or mutable collections.
- Cross-context commands use explicit application APIs. Cross-context reactions use committed events.
- Public HTTP and JSON boundaries preserve snake_case payloads.
- Persisted writes reach SQLite before in-memory projections are changed.

## Consequences

The codebase contains more explicit types and mappings than a conventional layered CRUD service. In return, terminal infrastructure, SQLite persistence, HTTP protocol handling, and domain behavior can be replaced or tested independently without leaking framework types into the domain.
