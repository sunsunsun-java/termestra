# Marketplace

Marketplace exposes the bundled, read-only catalog of role descriptions that a
user may inspect before importing one into their local configuration or team.

## Language

**Marketplace Catalog**:
The versioned, locale-specific tree of bundled role entries shipped with
Termestra.
_Avoid_: Remote store, Role Template repository

**Marketplace Agent**:
A read-only role description in the Marketplace Catalog.
_Avoid_: Agent, TeamMember, installed plugin

**Import**:
The explicit user action that copies Marketplace Agent content into a locally
owned Role Template or TeamMember.
_Avoid_: Install, execute, synchronize
