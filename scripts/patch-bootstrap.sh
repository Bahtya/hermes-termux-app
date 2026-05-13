#!/usr/bin/env bash
# Patch upstream Termux bootstrap ZIPs: replace /data/data/com.termux → /data/data/com.hermux
# Called from CI workflows before Gradle build.
#
# Usage: patch-bootstrap.sh <bootstrap_dir> [version]
#   bootstrap_dir  - absolute path to app/src/main/cpp
#   version        - upstream version string (default: 2026.02.12-r1%2Bapt.android-7)

set -euo pipefail

BOOTSTRAP_DIR="${1:?Usage: patch-bootstrap.sh <bootstrap_dir> [version]}"
VERSION="${2:-2026.02.12-r1%2Bapt.android-7}"

OLD="/data/data/com.termux"
NEW="/data/data/com.hermux"

mkdir -p "$BOOTSTRAP_DIR"

for arch in aarch64 arm i686 x86_64; do
  ZIP="${BOOTSTRAP_DIR}/bootstrap-${arch}.zip"
  if [ -f "${ZIP}" ]; then
    echo "${arch}: already exists, skipping"
    continue
  fi
  URL="https://github.com/termux/termux-packages/releases/download/bootstrap-${VERSION}/bootstrap-${arch}.zip"
  echo "Downloading ${arch}: ${URL}"
  curl -fSL -o "${ZIP}" "${URL}"
done

for arch in aarch64 arm i686 x86_64; do
  ZIP="${BOOTSTRAP_DIR}/bootstrap-${arch}.zip"
  echo "Patching ${arch}"
  TMPDIR=$(mktemp -d)
  cd "$TMPDIR"
  unzip -o "${ZIP}"

  [ -f SYMLINKS.txt ] && sed -i "s|${OLD}|${NEW}|g" SYMLINKS.txt

  # Text config files
  find . -type f \( -path './etc/*' -o -path './share/*' \) \
    -exec sed -i "s|${OLD}|${NEW}|g" {} +

  # dpkg database: info/*.list, info/*.postinst, info/*.prerm, status, etc.
  # These contain com.termux paths in file lists, maintainer script shebangs,
  # and update-alternatives calls. dpkg will fail if these aren't patched.
  find . -type f \( -path './var/*' \) \
    -exec sed -i "s|${OLD}|${NEW}|g" {} +

  # ELF binaries — verify each patched file is still a valid ELF
  find . -type f \( -path './bin/*' -o -path './lib/*' -o -path './libexec/*' \) | while read -r f; do
    perl -pi -e "s|/data/data/com\\.termux|/data/data/com\\.hermux|g" "$f"
    # Verify ELF magic (0x7F 'E' 'L' 'F') is intact after patching
    magic=$(dd if="$f" bs=4 count=1 2>/dev/null | od -A n -t x1 | tr -d ' \n')
    if [ "$magic" != "7f454c46" ]; then
      echo "WARNING: $f may be corrupted after ELF patching (magic=$magic)"
    fi
  done

  rm -f "${ZIP}"
  zip -r "${ZIP}" .
  cd - > /dev/null
  rm -rf "$TMPDIR"
done

echo "Pre-patched bootstrap ready"
