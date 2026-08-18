#!/bin/sh
set -eu

source_dir="$1"
output_root="$2"
version="$3"

case "$(uname -s)" in
  Darwin) platform="darwin" ;;
  Linux) platform="linux" ;;
  MINGW*|MSYS*|CYGWIN*) platform="win32" ;;
  *) echo "Unsupported release platform: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  arm64|aarch64) architecture="arm64" ;;
  x86_64|amd64) architecture="x64" ;;
  *) echo "Unsupported release architecture: $(uname -m)" >&2; exit 1 ;;
esac

package_dir="$output_root/runtime-$platform-$architecture"
if [ -d "$package_dir" ]; then
  rm -rf "$package_dir"
fi
mkdir -p "$package_dir"
cp -R "$source_dir/runtime" "$source_dir/app" "$package_dir/"
cp "$(dirname "$0")/../../LICENSE.BSL" "$package_dir/LICENSE.BSL"
cp "$(dirname "$0")/../../LICENSE" "$package_dir/LICENSE"
cp "$(dirname "$0")/../../NOTICE" "$package_dir/NOTICE"
cp "$(dirname "$0")/../../TRADEMARK.md" "$package_dir/TRADEMARK.md"
cp "$(dirname "$0")/../../THIRD_PARTY_NOTICES.md" "$package_dir/THIRD_PARTY_NOTICES.md"
sed -e "s/PLATFORM/$platform/g" -e "s/ARCH/$architecture/g" -e "s/VERSION/$version/g" \
  "$(dirname "$0")/../npm/runtime-template/package.json" > "$package_dir/package.json"
