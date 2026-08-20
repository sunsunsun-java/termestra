# ADR-0007: macOS-only distribution

- Status: Accepted
- Date: 2026-08-20

## Context

Termestra initially published runtime packages for macOS arm64/x64, Linux arm64/x64, and Windows
x64. Every runtime bundled a jlink Java image and one Spring Boot application JAR. Although npm
selected only the host runtime package, several dependencies inside that JAR still carried native
binaries for every supported and unsupported operating system. The Apple Silicon tarball reached
97,055,951 bytes, making installation sensitive to interrupted registry downloads.

Maintaining five native release jobs also required Windows Job Object ownership, Windows and Linux
shell/folder/open-target behavior, and platform-specific PTY validation. The product is now choosing
a narrower supported environment rather than retaining those contracts without equivalent ongoing
validation.

## Decision

Termestra supports only macOS on Apple Silicon and Intel.

- `@termestra/cli` has optional dependencies only on `runtime-darwin-arm64` and
  `runtime-darwin-x64`; launch attempts on other operating systems fail with an explicit unsupported
  platform message.
- Release CI builds, installs, and publishes exactly those two native runtimes. Previously published
  Linux and Windows versions remain historical npm artifacts and receive no new versions.
- Backend shell, folder-picker, open-target, command-probe, and PTY ownership behavior is macOS-only.
- Runtime assembly removes non-target SQLite, pty4j, JNA, and Netty native content from each
  architecture-specific application JAR and thins pty4j universal Mach-O files to the target slice.
  Distribution verification inspects nested JAR contents and Mach-O architectures, and rejects
  runtime tarballs larger than 75,000,000 bytes.
- The CLI postinstall step verifies that npm actually installed the host runtime. A skipped optional
  download is resumed into a CLI-owned recovery directory and checked against npm's SHA-512;
  exhausted recovery fails the installation instead of leaving a broken launcher.

## Consequences

The Apple Silicon `0.1.2-SNAPSHOT` release candidate built on 2026-08-20 is approximately 63.65 MB
packed and 83.66 MB unpacked, down from the published `0.1.1` package's 97.06 MB and 119.94 MB
respectively. Release confidence is concentrated on two native environments instead of five.
Windows Job Object code and Linux/Windows UI and shell branches no longer impose maintenance or
packaging costs.

Linux and Windows users cannot install a current runtime, and existing historical packages do not
represent current product behavior. Restoring another operating system is a product-contract change:
it requires a superseding ADR, a native CI runner, PTY/process lifecycle coverage, filesystem and
shell adapters, an npm runtime dependency, nested-native filtering for that target, and a published
package install smoke test. Adding only an npm package name is insufficient.
