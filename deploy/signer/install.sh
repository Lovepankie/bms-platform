#!/bin/bash
# Installs the image signer on the maintainer's Mac (ADR-040): the pinned tools, the script, and a launchd
# agent that runs it every 30 seconds. Run as the logged-in user, not root.
#
# Needs: crane and gh on the PATH (brew install crane gh), `gh auth login` done with a token that may write
# packages, and the Keychain items bms-cosign-key (base64 of the PEM) and bms-cosign-password.
# cosign is NOT taken from brew: it must be v2.4.1, the version the staging puller and the pointer job verify
# with. A newer cosign (v3) writes signatures in a format they cannot read, and the puller would then mark every
# release failed for good. The pinned binary is downloaded here and checked against a hash kept in this file.
set -euo pipefail
[ "$(id -u)" != "0" ] || { echo "run as your own user, not root" >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HOME/bms-ops/signer"
LABEL="com.rindzo.bms-signer"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
COSIGN_VERSION="v2.4.1"
case "$(uname -m)" in
  arm64) COSIGN_ASSET="cosign-darwin-arm64"; COSIGN_SHA256="13343856b69f70388c4fe0b986a31dde5958e444b41be22d785d3dc5e1a9cc62" ;;
  x86_64) COSIGN_ASSET="cosign-darwin-amd64"; COSIGN_SHA256="666032ca283da92b6f7953965688fd51200fdc891a86c19e05c98b898ea0af4e" ;;
  *) echo "unsupported architecture" >&2; exit 1 ;;
esac
for tool in crane gh python3 security curl shasum; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done
security find-generic-password -s bms-cosign-key >/dev/null 2>&1 || { echo "Keychain item bms-cosign-key is missing" >&2; exit 1; }
mkdir -p "$DEST/bin"
if ! "$DEST/bin/cosign" version 2>/dev/null | grep -q "$COSIGN_VERSION"; then
  tmp="$(mktemp)"
  curl -fsSL -o "$tmp" "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/${COSIGN_ASSET}"
  echo "${COSIGN_SHA256}  ${tmp}" | shasum -a 256 -c - >/dev/null || { echo "cosign download hash mismatch: refusing" >&2; rm -f "$tmp"; exit 1; }
  install -m 755 "$tmp" "$DEST/bin/cosign"; rm -f "$tmp"
fi
"$DEST/bin/cosign" version 2>/dev/null | grep -q "$COSIGN_VERSION" || { echo "cosign ${COSIGN_VERSION} did not install" >&2; exit 1; }
install -m 700 "$HERE/bms_signer.py" "$DEST/bms_signer.py"
PATHS="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/python3</string><string>${DEST}/bms_signer.py</string><string>--loop</string><string>30</string>
  </array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>${PATHS}</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>${DEST}/signer.log</string>
  <key>StandardErrorPath</key><string>${DEST}/signer.log</string>
</dict></plist>
PLIST_EOF
launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed: ${LABEL} with cosign ${COSIGN_VERSION}; log at ${DEST}/signer.log"
