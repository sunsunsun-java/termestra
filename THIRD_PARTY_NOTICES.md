# Third-Party Notices

This file records third-party material distributed with Termestra. It does not
replace the complete license texts stored next to the corresponding material,
and the Termestra project license does not relicense third-party works or
trademarks.

## Worker-role marketplace snapshots

### agency-agents (English snapshot)

- Included files: `backend/src/main/resources/vendor/marketplace/en/`
- Upstream: <https://github.com/msitarzewski/agency-agents>
- Pinned commit: `783f6a72bfd7f3135700ac273c619d92821b419a`
- License: MIT
- Copyright notice: Copyright (c) 2025 AgentLand Contributors
- Complete records: `backend/src/main/resources/vendor/marketplace/en/LICENSE`
  and `SOURCES.md`
- Modifications: the role Markdown is unmodified; top-level catalog and meta
  files are excluded when building the bundled snapshot.

### agency-agents-zh (Chinese snapshot)

- Included files: `backend/src/main/resources/vendor/marketplace/zh/`
- Upstream: <https://github.com/jnMetaCode/agency-agents-zh>
- Pinned commit: `13b8800f6f666e8e10ac64f67f1194d2baeefbe0`
- License: MIT
- Copyright notices: Copyright (c) 2025 Michael Sitarzewski (original English
  version); Copyright (c) 2026 jnMetaCode (Chinese translation and localization)
- Complete records: `backend/src/main/resources/vendor/marketplace/zh/LICENSE`
  and `SOURCES.md`
- Modifications: the role Markdown is unmodified; top-level catalog and meta
  files are excluded when building the bundled snapshot.

## Kenney Interface Sounds 1.0

- Included files: `frontend/web/public/sounds/*.ogg`
- Creator: Kenney, <https://www.kenney.nl/>
- License: Creative Commons Zero 1.0 Universal (`CC0-1.0`)
- Complete record: `frontend/web/public/sounds/LICENSE-KENNEY.txt`
- Attribution is appreciated by the creator but is not required by the supplied
  license record.

## Embedded Java runtime

Platform npm packages include a runtime image produced by `jlink`. The image's
`legal/` directory contains the notices and license texts supplied by the JDK
and its bundled third-party components. Those files must remain intact in every
platform runtime package.

## Third-party product names

Termestra uses third-party product names solely to identify compatible CLIs and
applications a user may select. Product names and trademarks belong to their
respective owners; their use does not imply affiliation or endorsement.

The product UI does not distribute third-party product icons, favicons, or
application artwork. It renders Termestra-neutral vector glyphs for compatible
CLI and open-target choices instead. The dated replacement record is
[`docs/governance/public-asset-remediation-2026-08-18.md`](docs/governance/public-asset-remediation-2026-08-18.md).

## Dependency notices

Source and binary dependencies remain subject to the licenses published by
their respective authors. Maven and pnpm lockfiles identify the exact dependency
versions used by this source tree. This notice is not a substitute for the
license metadata and notice files embedded in those dependencies.
