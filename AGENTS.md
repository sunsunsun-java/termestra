# AGENTS.md

Repository contract for AI-assisted work on Termestra. This file is the short,
always-loaded workflow; detailed facts live behind the pointers below.

## Source of truth

Resolve conflicts in this order:

1. current code and executable tests;
2. public HTTP, WebSocket, CLI, filesystem, and distribution contracts;
3. accepted records in [`docs/adr/`](docs/adr/README.md);
4. the current architecture guides in
   [`docs/architecture/`](docs/architecture/README.md);
5. README, roadmap, research, and dated review documents.

Read [`CONTEXT-MAP.md`](CONTEXT-MAP.md) before a change crosses backend
capabilities. Read the linked `CONTEXT.md` when changing that context's language
or rules. Read [`docs/README.md`](docs/README.md) when deciding which document
must change with the code.

## Repository shape

- `frontend/` — React 19, TypeScript, Vite, xterm.js, browser/PWA adapters.
- `backend/` — Java 21 Spring Boot modular monolith; all backend contexts live
  below `dev.termestra`.
- `distribution/` — jlink runtime and npm launcher/platform packages.
- `docs/` — current guides, accepted decisions, detailed designs, research,
  governance reviews, and product status.
- `scripts/` — repository-wide verification utilities.

Generated `target/`, `frontend/web/dist/`, and `frontend/node_modules/` content
is build output. Change its source and regenerate it.

## Change workflow

1. **Locate the owner.** Identify the context, its inbound use case, owned
   persistence, and public consumers. The step is complete when every modified
   behavior has one named owner and every crossed context appears in the context
   map.
2. **Trace the real contract.** Inspect the implementation plus its closest unit
   and boundary tests. For public changes, enumerate exact fields, states,
   limits, failure codes, and cleanup behavior before editing.
3. **Change through the owning interface.** Keep domain rules in the domain,
   orchestration in application modules, and framework or device behavior in
   adapters. Wire cross-context adapters in `RuntimeWiring`.
4. **Verify at the risk boundary.** Add or update the narrowest unit tests and
   the real HTTP, SQLite, PTY, WebSocket, filesystem, CLI, frontend, or packaging
   test that can observe the regression.
5. **Synchronize durable knowledge.** Update the relevant current guide and
   context language with the implementation. Add or replace an ADR only for a
   hard-to-reverse, surprising trade-off.

## Backend architecture

- A business context may contain `domain`, `application`, and `adapter` layers.
  Small supporting contexts may use a reduced shape.
- Domain code is plain Java and depends on neither application code nor Spring,
  Jackson, JDBC, Reactor/Netty, PTY, or filesystem types.
- Application code owns its inbound and outbound ports. It may depend on its
  domain and the small shared kernel, but not on adapters, platform, bootstrap,
  or runtime frameworks.
- Inbound adapters translate transport contracts into use cases. Outbound
  adapters implement application-owned ports. An inbound adapter never reaches
  directly into an outbound adapter.
- Normal writes stay inside the owning context. Workspace and Worker hard
  deletion are the documented exception: their initiating persistence adapter
  removes the complete cross-context lifecycle graph in one SQLite transaction;
  keep this exception deletion-only and boundary-tested.
- `platform` contains shared technical mechanisms, `shared` contains only stable
  IDs and runtime-coordination primitives, and `bootstrap` is the composition
  root.
- Preserve the mandatory rules in `ArchitectureTest`. A new Maven module needs
  an independent deployment, reuse, ownership, platform, or build-isolation
  lifecycle; package count alone is insufficient.

Prefer deep modules: a small interface that hides transaction, lifecycle, or
protocol complexity. Introduce a seam when at least two real adapters or a real
volatility boundary justify it; avoid pass-through layers.

## State, durability, and concurrency

- SQLite is authoritative for Workspace, Team, Execution, and Configuration
  state. Commit authoritative writes before invalidating projections, waking
  runtimes, or producing other in-memory effects.
- A Team send atomically admits its Message, Dispatch, and Team-owned Delivery
  outbox row. PTY input is non-transactional: preserve `uncertain` outcomes and
  require deliberate retry whenever input may have reached the process.
- Public Dispatch states remain `queued`, `submitted`, `reported`, and
  `cancelled`. Delivery states remain internal/recovery-facing. Public TeamMember
  states remain `idle`, `working`, and `stopped`; a dead PTY must become visibly
  `stopped`.
- Use `RuntimeOperationCoordinator` for Workspace and Agent runtime races. Keep
  exact resource keys, the shared acquisition deadline, typed busy failures,
  reference cleanup, and the ban on shared-to-exclusive upgrades.
- Persist deletion of Termestra metadata before cleaning live projections and
  processes. Never delete the user's selected source directory.
- Every registry, buffer, queue, cache, retained run set, watcher set, poll, and
  fan-out path has an explicit capacity and lifecycle cleanup.

## Public contracts

- HTTP and JSON wire fields use `snake_case`; TypeScript may map them to
  `camelCase` internally.
- Separate summary, detail, and stream interfaces. List and polling paths use
  dedicated bounded projections and never carry terminal history, document
  bodies, prompts, logs, or message history.
- Collection endpoints need a limit, pagination, or a proven product-level
  cardinality bound. Assert exact fields and response-size budgets for hot paths.
- WebSocket streams need an atomic snapshot-to-live handoff, bounded
  slow-consumer handling, and disconnect cleanup. The service worker never
  caches API or WebSocket traffic.
- Convert runtime causes to typed failures or stable `error_code` values. Keep
  test accommodations in test code.
- Generate new Workspace, Agent, Run, Dispatch, attempt, and idempotency IDs with
  `UUID.randomUUID()` or the shared ID factories that delegate to it.

## Test matrix

- Domain transition or policy: focused Java unit test.
- Application transaction or recovery: service test plus SQLite adapter test
  when durability matters.
- HTTP/JSON or auth: real WebFlux integration test asserting status and exact
  payload shape.
- PTY/process lifecycle: pty4j or platform process boundary test on applicable
  operating systems.
- Terminal or Tasks streaming: WebSocket integration test covering initial
  snapshot, live ordering, limits, reconnect, and cleanup.
- Frontend state or presentation: Node/Vitest test; keep polling, caches,
  optimistic state, and queues bounded.
- CLI or release behavior: picocli/npm/distribution smoke test against assembled
  output.

Run `mvn clean verify` from the repository root for every non-trivial change. It
installs locked frontend dependencies, type-checks/tests/builds the UI, runs Java
and architecture tests, verifies brand rules, and assembles/verifies the host
distribution. If it cannot complete, report the exact failing module, command,
and environmental blocker.

## Documentation maintenance

- Current behavior belongs in `docs/architecture/`; keep it code-navigable and
  free of proposals.
- Hard-to-reverse decisions belong in `docs/adr/`; never rewrite an accepted
  decision to pretend history changed—supersede it.
- Implementation-specific algorithms belong in `docs/design/`.
- External evaluations belong in `docs/research/` and are never product truth.
- Dated licensing or compliance evidence belongs in `docs/governance/`.
- Delivery status belongs in `docs/product/roadmap.md`.

When moving documentation, update README links, distribution staging, validation
scripts, and the indexes in the same change.
