#!/bin/sh
set -eu
output="$1"
runtime="$output/runtime"

# jlink requires a non-existent output directory. Maven's clean phase normally
# guarantees that, but this script is also used by incremental/package-only
# builds and by release tooling, so make the narrow generated directory
# idempotent here.
if [ -d "$runtime" ]; then
  rm -rf "$runtime"
fi
jlink --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=2 --output "$runtime"
