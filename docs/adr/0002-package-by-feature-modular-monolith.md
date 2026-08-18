# ADR-0002: Package-by-feature modular monolith

- Status: Accepted
- Date: 2026-08-07

## Context

The first Java migration represented nearly every domain, adapter, and architecture test suite as
an independent Maven artifact. The backend was a single deployable process, but thirteen production
JAR modules and separate bootstrap and contract-test modules made ordinary changes cross many build
boundaries. Several modules contained fewer than ten source files and had no independent release,
runtime, ownership, or scaling lifecycle.

Maven artifacts are useful deployment and reuse boundaries, but they are too expensive as a default
substitute for package encapsulation. Termestra needs strong domain boundaries without presenting a
microservice-shaped repository for a local desktop application.

## Decision

Termestra is organized as a frontend/backend monorepo with three build units:

1. `frontend`: the React/Vite client;
2. `backend`: one Spring Boot deployable organized as a package-based modular monolith;
3. `distribution`: jlink images and npm platform packages.

Inside `backend`, the first package segment expresses a business capability. A context with material
business rules uses local hexagonal layers:

```text
dev.termestra.<context>/
├── domain/          # entities, value objects, domain rules
├── application/     # use cases and owned inbound/outbound ports
└── adapter/
    ├── in/          # HTTP and other driving adapters
    └── out/         # SQLite, filesystem, PTY and other driven adapters
```

Simple supporting contexts are allowed to use a smaller local structure. DDD layers are introduced
to protect real domain complexity, not to manufacture empty directories.

`dev.termestra.platform` contains reusable technical mechanisms such as WebFlux, CLI, and SQLite
bootstrap code. `dev.termestra.shared` is intentionally small and contains only stable identifiers
and domain-event primitives shared by multiple contexts. `dev.termestra.bootstrap` remains the only
composition root.

Package dependencies are enforced with ArchUnit:

- domain code cannot depend on application, adapters, platform, bootstrap, or frameworks;
- application code cannot depend on adapters, platform, bootstrap, or runtime frameworks;
- inbound adapters cannot call outbound adapters or persistence directly;
- the shared kernel cannot depend on a bounded context or a framework.

## When to add another Maven module

A new backend Maven module requires at least one concrete independent lifecycle: separate deployment,
external reuse, materially different platform dependencies, independent ownership, or a build-time
isolation need that package rules cannot enforce. Source-file count alone is not a reason.

## Consequences

The reactor shrinks from seventeen modules to three, while domain boundaries become easier to see
because packages are grouped by capability rather than technology. Backend tests and architecture
rules run in one module, and the npm distribution still consumes one Spring Boot JAR. The tradeoff is
that package visibility and ArchUnit, rather than Maven's compiler classpath, now enforce most internal
boundaries.
