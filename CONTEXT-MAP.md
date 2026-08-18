# Context Map

Termestra is a package-by-feature modular monolith. A context owns its language,
rules, persistence/projections, and application interfaces; `platform`, `shared`,
and `bootstrap` are supporting modules rather than business contexts.

## Contexts

- [Workspace](backend/src/main/java/dev/termestra/workspace/CONTEXT.md) — owns a
  trusted local Workspace's identity, name, canonical directory, registration,
  and lifecycle.
- [Team](backend/src/main/java/dev/termestra/team/CONTEXT.md) — owns visible
  TeamMembers, Dispatch business state, Delivery recovery state, reports,
  cancellation, and scenario membership.
- [Agent Execution](backend/src/main/java/dev/termestra/execution/CONTEXT.md) —
  owns launch configuration, real CLI/PTY Run lifecycle, process credentials,
  bounded output, provider session capture, and restart recovery.
- [Terminal](backend/src/main/java/dev/termestra/terminal/CONTEXT.md) — owns the
  browser viewing protocol for a Run: restore, resize, input/control channels,
  per-viewer flow control, and terminal-screen projection.
- [Tasks](backend/src/main/java/dev/termestra/tasks/CONTEXT.md) — owns
  `.termestra/tasks.md` synchronization, revision conflicts, file watching, and
  the generated `.termestra/PROTOCOL.md` guide.
- [Configuration](backend/src/main/java/dev/termestra/configuration/CONTEXT.md) —
  owns command presets, role templates, command availability, and small local UI
  application state.
- [Marketplace](backend/src/main/java/dev/termestra/marketplace/CONTEXT.md) — owns
  the read-only bundled role catalog and locale-specific role-template content.
- [Auth](backend/src/main/java/dev/termestra/auth/CONTEXT.md) — owns process-local
  UI sessions and per-Run Agent credentials for the loopback runtime.

## Relationships

- **Workspace -> Tasks**: Workspace asks Tasks to initialize its metadata files;
  Tasks looks up the registered Workspace path but never owns Workspace identity.
- **Workspace -> Agent Execution**: Workspace prepares the logical Orchestrator's
  launch configuration and may start or forget its runtime.
- **Workspace -> Team / Agent Execution / Configuration**: deleting a Workspace
  removes its complete Termestra-owned lifecycle graph in one SQLite transaction;
  this is a documented deletion-only exception to normal context-owned writes.
- **Team -> Agent Execution**: Team adapters provision/start real Worker Runs and
  submit committed Dispatch content or notifications to a PTY.
- **Team -> Configuration**: scenario provisioning resolves role and command
  defaults through Configuration's application interfaces.
- **Team -> Auth**: Team validates the actor credential before accepting the
  `team` protocol command.
- **Team -> Agent Execution (deletion)**: deleting a Worker removes that
  TeamMember's launch, Run, and provider-session rows in the same SQLite
  transaction before live runtime cleanup.
- **Agent Execution -> Configuration**: launch preparation resolves command
  presets and preset augmentation at the composition seam.
- **Agent Execution -> Team**: restart recovery reads bounded recent member and
  Dispatch context; it does not mutate Team state.
- **Terminal -> Agent Execution**: Terminal observes and controls Runs through
  `TerminalRuntimeGateway`; it does not own processes or durable run status.
- **Tasks -> Workspace**: Tasks resolves the Workspace directory through a
  read-only location port and owns only files beneath `.termestra/`.
- **Marketplace -> Configuration/Team**: the UI reads Marketplace content and
  explicitly creates a Configuration role template or TeamMember; Marketplace
  never writes those contexts itself.

## Supporting modules

- [`shared`](backend/src/main/java/dev/termestra/shared) — stable identifiers and
  exact-key runtime coordination shared across contexts.
- [`platform`](backend/src/main/java/dev/termestra/platform) — SQLite migration,
  process, common web error, and CLI mechanisms used by adapters.
- [`bootstrap`](backend/src/main/java/dev/termestra/bootstrap) — Spring entry point
  and the only composition root; cross-context adapters are assembled in
  `RuntimeWiring`.

See [the architecture overview](docs/architecture/overview.md) for the runtime
view and [contracts and data](docs/architecture/contracts-and-data.md) for table
ownership and public state machines.
