# Product delivery status

> Last reconciled with the current source tree: 2026-08-19.

Termestra now has an executable vertical slice through every core product
surface. Checked items have automated coverage in the Maven reactor unless a
platform validation item explicitly says otherwise.

## Phase 0: Foundation

- [x] Three-unit Maven reactor and package-bounded backend contexts.
- [x] Architecture decision records and ArchUnit dependency rules.
- [x] Strong identifiers and aggregate tests.
- [x] Real-boundary HTTP, SQLite, PTY, WebSocket, filesystem, CLI, and distribution tests.

## Phase 1: Persistence and workspace

- [x] Versioned SQLite migrations with preservation tests.
- [x] Workspace, worker, settings, presets, role templates, and marketplace repositories.
- [x] Workspace, filesystem, native picker, open-target, and settings HTTP contracts.
- [x] Transactional hard deletion for Workspace and Worker lifecycle graphs.

## Phase 2: Team protocol

- [x] Dispatch aggregate and ledger.
- [x] Message log, send, report, status, cancellation, filtering, and authorization.
- [x] Failed launch/delivery rollback leaves no message, dispatch, or pending-work residue.
- [x] Java/picocli `team` client plus npm-installed `team` entrypoint.

## Phase 3: Agent execution

- [x] pty4j process adapter and output bus.
- [x] Launch resolution, provider presets, YOLO augmentation, startup-command shell semantics.
- [x] Claude/Codex/Gemini/OpenCode session capture and resume.
- [x] Prompt-aware bracketed-paste startup instructions and DB-first run lifecycle.
- [x] Two-layer restart policy: native provider session resume first, persisted recovery summary fallback otherwise.
- [x] DB-first user input and recovery messages with failed PTY injection rollback.

## Phase 4: Terminal and tasks

- [x] Dual-channel terminal WebSocket protocol and browser-compatible text output frames.
- [x] Restore, resize, exit, error, acknowledgement, and real PTY WebSocket coverage.
- [x] Cell-oriented headless terminal mirror and per-viewer high/low-water flow control.
- [x] Tasks document migration, watcher, writes, and tasks WebSocket.
- [x] Auto-generated, version-refreshed `.termestra/PROTOCOL.md` recovery guide without needless rewrites.

## Phase 5: UI and distribution

- [x] React/xterm.js UI integrated with the Java runtime.
- [x] Host jlink runtime and npm optional platform-package assembly.
- [x] `termestra`, `team`, `update`, port/help/version, and package smoke paths.
- [x] Native CI/package/publish matrix for macOS arm64/x64, Linux arm64/x64, and Windows x64.
- [x] `.tgz` transport, isolated global-install smoke coverage, public package metadata, and runtime-first npm release ordering.
- [x] Replace third-party product/app imagery with neutral vectors and regenerate the README tour from the anonymous built-in Demo; retain the dated public-asset remediation record.
- [ ] Observe one successful five-platform tag build before the first production publish.

## Phase 6: Release readiness

- [x] Pin destructive deletion and failed-delivery rollback through SQLite fault injection and real HTTP boundaries.
- [x] Add terminal stress, recovery, and assembled npm-runtime tests to Maven and CI.
- [x] Keep list and polling endpoints bounded independently of retained terminal history.
- [ ] Verify macOS, Linux, and Windows behavior from published packages before a production cutover.
- [ ] Complete one successful five-platform tagged build and published-package install smoke test.
