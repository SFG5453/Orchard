#!/bin/sh
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

set -eu

target=${1:-}
case "$target" in
  linux-x64)
    deb_arch=amd64
    rpm_arch=x86_64
    ;;
  linux-arm64)
    deb_arch=arm64
    rpm_arch=aarch64
    ;;
  *)
    echo "Usage: $0 linux-x64|linux-arm64" >&2
    exit 1
    ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
manager_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
repository_dir=$(CDPATH= cd -- "$manager_dir/.." && pwd)
version=$(cd "$manager_dir" && node -p "require('./package.json').version")
major_version="${version%%.*}.0.0"
bundle="$manager_dir/dist/releases/orchard-packages-$version-$target"
output_dir="$repository_dir/artifacts/linux/$target"
icon="$manager_dir/resources/icons/orchard-packages.png"
wrapper_template="$repository_dir/packaging/linux/common/orchard-packages.sh.in"
desktop_file="$repository_dir/packaging/linux/common/dev.sfg.orchard.desktop"

if [ ! -d "$bundle" ]; then
  (cd "$manager_dir" && bun run scripts/build-release.ts "$target")
fi

for required in \
  "$bundle/orchard-packages" \
  "$bundle/resources.neu" \
  "$bundle/extensions/orchard-packages-backend" \
  "$icon" \
  "$wrapper_template" \
  "$desktop_file" \
  "$repository_dir/LICENSE"; do
  if [ ! -f "$required" ]; then
    echo "Required Orchard Packages input is missing: $required" >&2
    exit 1
  fi
done

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/orchard-packages-linux.XXXXXX")
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT HUP INT TERM

payload="$work_dir/payload"
mkdir -p \
  "$payload/usr/bin" \
  "$payload/usr/lib/orchard-packages/extensions" \
  "$payload/usr/share/applications" \
  "$payload/usr/share/icons/hicolor/1024x1024/apps" \
  "$payload/usr/share/licenses/orchard-packages" \
  "$payload/usr/share/doc/orchard-packages"

install -m 755 "$bundle/orchard-packages" \
  "$payload/usr/lib/orchard-packages/orchard-packages"
install -m 644 "$bundle/resources.neu" \
  "$payload/usr/lib/orchard-packages/resources.neu"
install -m 755 "$bundle/extensions/orchard-packages-backend" \
  "$payload/usr/lib/orchard-packages/extensions/orchard-packages-backend"
sed "s/@ORCHARD_MAJOR_VERSION@/$major_version/g" "$wrapper_template" > "$payload/usr/bin/orchard"
chmod 755 "$payload/usr/bin/orchard"
ln -s ../lib/orchard-packages/orchard-packages "$payload/usr/bin/orchard-packages"
install -m 644 "$desktop_file" \
  "$payload/usr/share/applications/dev.sfg.orchard.desktop"
install -m 644 "$icon" \
  "$payload/usr/share/icons/hicolor/1024x1024/apps/dev.sfg.orchard.png"
install -m 644 "$repository_dir/LICENSE" \
  "$payload/usr/share/licenses/orchard-packages/LICENSE"
install -m 644 "$repository_dir/LICENSE" \
  "$payload/usr/share/doc/orchard-packages/copyright"

rm -rf -- "$output_dir"
mkdir -p "$output_dir"

deb_root="$work_dir/deb"
cp -a "$payload/." "$deb_root/"
mkdir -p "$deb_root/DEBIAN"
installed_size=$(du -sk "$payload" | awk '{ print $1 }')
cat > "$deb_root/DEBIAN/control" <<EOF
Package: orchard-packages
Version: $version
Section: sound
Priority: optional
Architecture: $deb_arch
Maintainer: SFG545 <sfg@sfg545.dev>
Installed-Size: $installed_size
Depends: libgtk-3-0 | libgtk-3-0t64, libwebkit2gtk-4.1-0 | libwebkit2gtk-4.1-0t64
Provides: orchard
Conflicts: orchard
Replaces: orchard
Homepage: https://github.com/sfg5453/orchard
Description: Orchard package installer and version manager
 Orchard is a YouTube Music desktop client backed by InnerTube.
 This package installs Orchard Packages, which downloads and updates the
 platform-specific Orchard application without bundling a second copy of it.
EOF
dpkg-deb --build --root-owner-group "$deb_root" \
  "$output_dir/orchard-packages_${version}_${deb_arch}.deb"

rpm_version="$version"
rpm_release=1
case "$version" in
  *-*)
    rpm_version=${version%%-*}
    rpm_release="0.${version#*-}"
    ;;
esac
rpm_release=$(printf '%s' "$rpm_release" | tr -c '[:alnum:]_.' '.')

rpm_top="$work_dir/rpm"
mkdir -p \
  "$rpm_top/BUILD" \
  "$rpm_top/BUILDROOT" \
  "$rpm_top/RPMS" \
  "$rpm_top/SOURCES" \
  "$rpm_top/SPECS" \
  "$rpm_top/SRPMS"
tar \
  --sort=name \
  --mtime=@0 \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  -czf "$rpm_top/SOURCES/orchard-packages-payload.tar.gz" \
  -C "$payload" .

rpm_spec="$rpm_top/SPECS/orchard-packages.spec"
cat > "$rpm_spec" <<EOF
Name: orchard-packages
Version: $rpm_version
Release: $rpm_release
Summary: Orchard package installer and version manager
License: AGPL-3.0-or-later
URL: https://github.com/sfg5453/orchard
BuildArch: $rpm_arch
Requires: gtk3
Requires: webkit2gtk4.1
Provides: orchard
Obsoletes: orchard
Source0: orchard-packages-payload.tar.gz

%description
Orchard is a YouTube Music desktop client backed by InnerTube. This package
installs Orchard Packages, which downloads and updates the platform-specific
Orchard application without bundling a second copy of it.

%prep
%setup -q -c -T

%install
rm -rf %{buildroot}
mkdir -p %{buildroot}
tar -xzf %{SOURCE0} -C %{buildroot}

%files
%defattr(-,root,root,-)
%license %{_prefix}/share/licenses/orchard-packages/LICENSE
%{_bindir}/orchard
%{_bindir}/orchard-packages
%dir %{_prefix}/lib/orchard-packages
%dir %{_prefix}/lib/orchard-packages/extensions
%{_prefix}/lib/orchard-packages/orchard-packages
%{_prefix}/lib/orchard-packages/resources.neu
%{_prefix}/lib/orchard-packages/extensions/orchard-packages-backend
%{_datadir}/applications/dev.sfg.orchard.desktop
%{_datadir}/icons/hicolor/1024x1024/apps/dev.sfg.orchard.png
%{_prefix}/share/doc/orchard-packages/copyright
EOF

rpmbuild \
  --define "_topdir $rpm_top" \
  --define "_builddir $rpm_top/BUILD" \
  --define "_buildrootdir $rpm_top/BUILDROOT" \
  --define "_rpmdir $rpm_top/RPMS" \
  --define "_srcrpmdir $rpm_top/SRPMS" \
  --define "_specdir $rpm_top/SPECS" \
  --define "_sourcedir $rpm_top/SOURCES" \
  --target "$rpm_arch" \
  -bb "$rpm_spec"

rpm_file=$(find "$rpm_top/RPMS" -type f -name 'orchard-packages-*.rpm' ! -name '*-debuginfo-*.rpm' ! -name '*-debugsource-*.rpm' -print -quit)
if [ -z "$rpm_file" ] || [ ! -f "$rpm_file" ]; then
  echo "RPM build did not produce a package." >&2
  exit 1
fi
cp "$rpm_file" "$output_dir/orchard-packages-${rpm_version}-${rpm_release}.${rpm_arch}.rpm"

find "$output_dir" -maxdepth 1 -type f -print | sort
