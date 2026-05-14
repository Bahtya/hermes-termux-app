#!/data/data/com.termux/files/usr/bin/bash
# Build hermes-agent Python venv inside Termux Docker.
# Called by .github/workflows/build-venv.yml via:
#   docker run --platform linux/arm64 --rm -v $PWD:/output termux/termux-docker bash /output/scripts/build-venv-termux.sh

set -e

HERMES_COMMIT="${HERMES_COMMIT:-486b692ddd801f8f665d3fff023149fb1cb6509e}"
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
HERMES_DIR="$HOME_DIR/.hermes/hermes-agent"
VENV_DIR="$HERMES_DIR/venv"
OUTPUT_DIR="/output"

echo "=== Step 1: Install system packages ==="
pkg update -y
pkg install -y python git rust make clang pkg-config libffi openssl ca-certificates

echo "=== Step 2: Clone hermes-agent ==="
rm -rf "$HERMES_DIR"
git clone https://github.com/NousResearch/hermes-agent.git "$HERMES_DIR"
cd "$HERMES_DIR"
git checkout "$HERMES_COMMIT"

echo "=== Step 3: Create venv ==="
rm -rf "$VENV_DIR"
python -m venv "$VENV_DIR"

echo "=== Step 4: Build psutil with Android patches ==="
PSUTIL_VER="7.2.2"
PSUTIL_TMP="$PREFIX/tmp/psutil-build"
rm -rf "$PSUTIL_TMP"
mkdir -p "$PSUTIL_TMP"
cd "$PSUTIL_TMP"
curl -fsSL "https://files.pythonhosted.org/packages/source/p/psutil/psutil-${PSUTIL_VER}.tar.gz" | tar xz
cd "psutil-${PSUTIL_VER}"
sed -i 's/platform android is not supported/platform android - building with Termux toolchain/g' pyproject.toml
sed -i 's/LINUX = sys.platform.startswith("linux")/LINUX = sys.platform.startswith(("linux", "android"))/g' psutil/_common.py
"$VENV_DIR/bin/pip" install --no-build-isolation .

echo "=== Step 5: Install hermes-agent ==="
cd "$HERMES_DIR"
if [ -f constraints-termux.txt ]; then
  "$VENV_DIR/bin/pip" install -c constraints-termux.txt ".[termux-all]"
else
  "$VENV_DIR/bin/pip" install ".[termux-all]"
fi

echo "=== Step 6: Validate venv ==="
"$VENV_DIR/bin/python" -c "import hermes_cli; print('venv OK: hermes_cli imported')"

echo "=== Step 7: Pack venv ==="
cd "$HERMES_DIR"
tar czf "$OUTPUT_DIR/venv-aarch64.tar.gz" venv/

echo "=== Done ==="
ls -lh "$OUTPUT_DIR/venv-aarch64.tar.gz"
