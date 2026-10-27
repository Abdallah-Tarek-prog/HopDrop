#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/out"
publish="$out/publish"
version="1.3.0-linux-preview.1"
deb_version="${version/-/\~}"

if [[ "${1:-}" != "--package-only" ]]; then
  dotnet publish "$here/HopDrop.Linux/HopDrop.Linux.csproj" -c Release -r linux-x64 --self-contained true -p:UsedAvaloniaProducts= -o "$publish"
fi
if [[ ! -f "$publish/HopDrop.Linux" ]]; then
  echo "Missing Linux publish at $publish; run without --package-only first." >&2
  exit 1
fi
mkdir -p "$out"
chmod +x "$publish/HopDrop.Linux"
cp "$here/packaging/run-hopdrop" "$publish/run-hopdrop"
chmod +x "$publish/run-hopdrop"
cp "$here/../../LICENSE" "$publish/HopDrop-LICENSE.txt"
cp "$here/README.md" "$publish/HopDrop-README.md"
printf '%s\n' "Source for this preview: https://github.com/Abdallah-Tarek-prog/HopDrop/tree/v$version" > "$publish/HopDrop-SOURCE.txt"
tar -C "$publish" -czf "$out/hopdrop-linux-x64-$version.tar.gz" .

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/DEBIAN" "$stage/opt/hopdrop" "$stage/usr/share/applications" "$stage/usr/share/icons/hicolor/256x256/apps"
cp -a "$publish/." "$stage/opt/hopdrop/"
cp "$here/packaging/hopdrop.desktop" "$stage/usr/share/applications/hopdrop.desktop"
cp "$here/HopDrop.Linux/Assets/HopDrop.png" "$stage/usr/share/icons/hicolor/256x256/apps/hopdrop.png"
find "$stage" -type d -exec chmod 755 {} +
find "$stage" -type f -exec chmod 644 {} +
chmod 755 "$stage/opt/hopdrop/HopDrop.Linux" "$stage/opt/hopdrop/run-hopdrop"
cat > "$stage/DEBIAN/control" <<EOF
Package: hopdrop
Version: $deb_version
Section: net
Priority: optional
Architecture: amd64
Maintainer: HopDrop <noreply@hopdrop.local>
Depends: ca-certificates, libc6, libgcc-s1, libgssapi-krb5-2, libstdc++6, tzdata, zlib1g, libssl3t64 | libssl3, libicu78 | libicu76 | libicu74 | libicu72 | libicu70, libx11-6, libice6, libsm6, libfontconfig1, xdg-utils, libnotify-bin
Recommends: libsecret-tools, network-manager, dnsmasq-base, bluez, bluez-obexd, blueman, libglib2.0-bin
Description: Local file and text transfers with HopDrop
 A desktop peer for sending and receiving files, folders, and text on a
 private local network or an existing hotspot.
EOF
# GitHub normalizes '~' in asset names, so keep the download name SemVer-safe.
dpkg-deb --build --root-owner-group "$stage" "$out/hopdrop_${version}_amd64.deb"
echo "Created $out/hopdrop-linux-x64-$version.tar.gz and $out/hopdrop_${version}_amd64.deb"
