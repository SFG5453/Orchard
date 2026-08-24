#!/bin/sh

set -eu

target=${1:-}
case "$target" in
  linux-x64) flatpak_arch=x86_64 ;;
  linux-arm64) flatpak_arch=aarch64 ;;
  *) echo "Usage: $0 linux-x64|linux-arm64" >&2; exit 1 ;;
esac

manager_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
repository_dir=$(CDPATH= cd -- "$manager_dir/.." && pwd)
flatpak_dir="$repository_dir/packaging/flatpak"
version=$(bun -e "const value = await Bun.file('$manager_dir/package.json').json(); process.stdout.write(value.version)")
release_dir="$manager_dir/dist/releases/orchard-packages-$version-$target"
output_dir="$repository_dir/artifacts/flatpak"
output="$output_dir/orchard-packages-$version-$target.flatpak"

if [ ! -d "$release_dir" ]; then
  bun run "$manager_dir/scripts/build-release.ts" "$target"
fi

rm -rf -- "$flatpak_dir/bundle" "$flatpak_dir/build" "$flatpak_dir/repo"
mkdir -p "$flatpak_dir/bundle/extensions" "$output_dir"
cp "$release_dir/orchard-packages" "$flatpak_dir/bundle/orchard-packages"
cp "$release_dir/resources.neu" "$flatpak_dir/bundle/resources.neu"
cp "$release_dir/extensions/orchard-packages-backend" "$flatpak_dir/bundle/extensions/orchard-packages-backend"
cp "$flatpak_dir/orchard" "$flatpak_dir/bundle/orchard"
cp "$flatpak_dir/orchard-packages-launcher" "$flatpak_dir/bundle/orchard-packages-launcher"
cp "$repository_dir/packaging/arch/orchard-packages.desktop" "$flatpak_dir/bundle/dev.sfg.orchard.desktop"
cp "$flatpak_dir/dev.sfg.orchard.png" "$flatpak_dir/bundle/dev.sfg.orchard.png"
cp "$flatpak_dir/dev.sfg.orchard.metainfo.xml" "$flatpak_dir/bundle/dev.sfg.orchard.metainfo.xml"

flatpak-builder --disable-rofiles-fuse --arch="$flatpak_arch" --force-clean --repo="$flatpak_dir/repo" \
  "$flatpak_dir/build" "$flatpak_dir/dev.sfg.orchard.yml"
flatpak build-bundle --arch="$flatpak_arch" \
  "$flatpak_dir/repo" "$output" dev.sfg.orchard

printf '%s\n' "$output"
