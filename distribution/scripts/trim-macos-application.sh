#!/bin/sh
set -eu

source_jar="$1"
output_jar="$2"
architecture="$3"

case "$architecture" in
  arm64)
    sqlite_opposite="x86_64"
    jna_opposite="x86-64"
    netty_opposite="osx-x86_64"
    macho_architecture="arm64"
    ;;
  x64)
    sqlite_opposite="aarch64"
    jna_opposite="aarch64"
    netty_opposite="osx-aarch_64"
    macho_architecture="x86_64"
    ;;
  *)
    echo "Unsupported macOS architecture: $architecture" >&2
    exit 1
    ;;
esac

workspace="$(mktemp -d /tmp/termestra-macos-application.XXXXXX)"
trap 'rm -rf "$workspace"' EXIT HUP INT TERM
mkdir -p "$workspace/BOOT-INF/lib" "$(dirname "$output_jar")"
cp "$source_jar" "$workspace/termestra.jar"

unzip -q "$workspace/termestra.jar" \
  'BOOT-INF/lib/sqlite-jdbc-*.jar' \
  'BOOT-INF/lib/pty4j-*.jar' \
  'BOOT-INF/lib/jna-*.jar' \
  -d "$workspace"

sqlite_jar="$(find "$workspace/BOOT-INF/lib" -name 'sqlite-jdbc-*.jar' -type f -print -quit)"
pty4j_jar="$(find "$workspace/BOOT-INF/lib" -name 'pty4j-*.jar' -type f -print -quit)"
jna_jar="$(find "$workspace/BOOT-INF/lib" -name 'jna-[0-9]*.jar' -type f -print -quit)"
jna_platform_jar="$(find "$workspace/BOOT-INF/lib" -name 'jna-platform-*.jar' -type f -print -quit)"
if [ -z "$sqlite_jar" ] || [ -z "$pty4j_jar" ] || [ -z "$jna_jar" ] || [ -z "$jna_platform_jar" ]; then
  echo "Application jar is missing SQLite, pty4j, or JNA" >&2
  exit 1
fi

zip -q -d "$sqlite_jar" \
  'org/sqlite/native/Linux*/*' \
  'org/sqlite/native/Windows/*' \
  'org/sqlite/native/FreeBSD/*' \
  "org/sqlite/native/Mac/$sqlite_opposite/*"

zip -q -d "$pty4j_jar" \
  'resources/com/pty4j/native/win/*' \
  'resources/com/pty4j/native/linux/*' \
  'resources/com/pty4j/native/freebsd/*' \
  'com/pty4j/windows/*'

pty4j_native_root="$workspace/pty4j-native"
unzip -q "$pty4j_jar" 'resources/com/pty4j/native/darwin/*' -d "$pty4j_native_root"
for native_name in libpty.dylib pty4j-unix-spawn-helper; do
  native_file="$pty4j_native_root/resources/com/pty4j/native/darwin/$native_name"
  thin_file="$native_file.thin"
  lipo "$native_file" -thin "$macho_architecture" -output "$thin_file"
  mv "$thin_file" "$native_file"
done
chmod 755 "$pty4j_native_root/resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper"
(
  cd "$pty4j_native_root"
  zip -q "$pty4j_jar" \
    'resources/com/pty4j/native/darwin/libpty.dylib' \
    'resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper'
)

zip -q -d "$jna_jar" \
  'com/sun/jna/aix-*/*' \
  'com/sun/jna/freebsd-*/*' \
  'com/sun/jna/linux-*/*' \
  'com/sun/jna/openbsd-*/*' \
  'com/sun/jna/sunos-*/*' \
  'com/sun/jna/win32-*/*' \
  "com/sun/jna/darwin-$jna_opposite/*" \
  'com/sun/jna/win32/*'

zip -q -d "$jna_platform_jar" \
  'com/sun/jna/platform/linux/*' \
  'com/sun/jna/platform/win32/*' \
  'com/sun/jna/platform/wince/*' \
  'com/sun/jna/platform/unix/aix/*' \
  'com/sun/jna/platform/unix/solaris/*'

zip -q -d "$workspace/termestra.jar" 'BOOT-INF/classpath.idx'
if unzip -Z1 "$workspace/termestra.jar" | grep -q "$netty_opposite"; then
  zip -q -d "$workspace/termestra.jar" "BOOT-INF/lib/*-$netty_opposite.jar"
fi

(
  cd "$workspace"
  zip -q -0 termestra.jar \
    "BOOT-INF/lib/$(basename "$sqlite_jar")" \
    "BOOT-INF/lib/$(basename "$pty4j_jar")" \
    "BOOT-INF/lib/$(basename "$jna_jar")" \
    "BOOT-INF/lib/$(basename "$jna_platform_jar")"
)
cp "$workspace/termestra.jar" "$output_jar"
