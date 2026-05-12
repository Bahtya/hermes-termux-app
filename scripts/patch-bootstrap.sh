#!/usr/bin/env bash
# Patch upstream Termux bootstrap ZIPs: replace /data/data/com.termux → /data/data/com.bahtya
# Called from CI workflows before Gradle build.
#
# Usage: patch-bootstrap.sh <bootstrap_dir> [version]
#   bootstrap_dir  - absolute path to app/src/main/cpp
#   version        - upstream version string (default: 2026.02.12-r1%2Bapt.android-7)

set -euo pipefail

BOOTSTRAP_DIR="${1:?Usage: patch-bootstrap.sh <bootstrap_dir> [version]}"
VERSION="${2:-2026.02.12-r1%2Bapt.android-7}"

OLD="/data/data/com.termux"
NEW="/data/data/com.bahtya"

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

  find . -type f \( -path './etc/*' -o -path './share/*' \) \
    -exec sed -i "s|${OLD}|${NEW}|g" {} +

  find . -type f \( -path './bin/*' -o -path './lib/*' -o -path './libexec/*' \) | while read -r f; do
    perl -pi -e "s|/data/data/com\\.termux|/data/data/com\\.bahtya|g" "$f"
  done

  rm -f "${ZIP}"
  zip -r "${ZIP}" .
  cd - > /dev/null
  rm -rf "$TMPDIR"
done

echo "Pre-patched bootstrap ready"
