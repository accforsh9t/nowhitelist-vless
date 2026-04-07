#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ $EUID -ne 0 ]]; then
  echo "Run this script as root (or with sudo)." >&2
  exit 1
fi

if [[ -f "$PROJECT_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$PROJECT_ROOT/.env"
  set +a
fi

if [[ -z "${WHITELIST_URL:-}" ]]; then
  # allow fallback from .env.example if .env was not prepared
  WHITELIST_URL="https://raw.githubusercontent.com/kulikov0/whitelist-bypass/main/whitelist.txt"
fi

XRAY_PORT="${XRAY_PORT:-443}"
SERVER_SNI="${SERVER_SNI:-www.microsoft.com}"
SERVER_HOST_IP="${SERVER_HOST_IP:-}"
XRAY_UUID="${XRAY_UUID:-$(xray uuid 2>/dev/null || true)}"
XRAY_SHORT_ID="${XRAY_SHORT_ID:-$(openssl rand -hex 4)}"
XRAY_CONFIG_DIR="/usr/local/etc/xray"
STATE_DIR="/etc/whitelist-vless"
CONFIG_PATH="$XRAY_CONFIG_DIR/config.json"
KEY_FILE_PREFIX="$STATE_DIR/reality"

mkdir -p "$XRAY_CONFIG_DIR" "$STATE_DIR"

if ! command -v xray >/dev/null; then
  echo "Installing Xray..." >&2
  apt-get update
  apt-get install -y curl jq openssl ca-certificates
  bash -c 'bash <(curl -fsSL https://raw.githubusercontent.com/XTLS/Xray-install/main/install-release.sh) @ install'
else
  apt-get update
  apt-get install -y curl jq openssl ca-certificates
fi

if [[ -z "$SERVER_HOST_IP" ]]; then
  SERVER_HOST_IP="$(curl -fsSL ifconfig.me)"
fi

if [[ -n "${XRAY_PUBLIC_KEY:-}" && -n "${XRAY_PRIVATE_KEY:-}" ]]; then
  PUBLIC_KEY="$XRAY_PUBLIC_KEY"
  PRIVATE_KEY="$XRAY_PRIVATE_KEY"
elif [[ -s "$KEY_FILE_PREFIX.public" && -s "$KEY_FILE_PREFIX.private" ]]; then
  PUBLIC_KEY="$(cat "$KEY_FILE_PREFIX.public")"
  PRIVATE_KEY="$(cat "$KEY_FILE_PREFIX.private")"
else
  echo "Generating Reality keypair..." >&2
  KEYPAIR_OUTPUT="$(xray x25519)"
  PRIVATE_KEY="$(awk '/Private key:/{print $3}' <<< "$KEYPAIR_OUTPUT" | head -n1)"
  PUBLIC_KEY="$(awk '/Public key:/{print $3}' <<< "$KEYPAIR_OUTPUT" | head -n1)"
  if [[ -z "$PRIVATE_KEY" || -z "$PUBLIC_KEY" ]]; then
    echo "Failed to generate x25519 keys." >&2
    exit 1
  fi
  echo "$PRIVATE_KEY" > "$KEY_FILE_PREFIX.private"
  echo "$PUBLIC_KEY" > "$KEY_FILE_PREFIX.public"
fi

if [[ -z "${XRAY_UUID:-}" ]]; then
  XRAY_UUID="$(xray uuid)"
fi

cat > "$CONFIG_PATH" <<EOF
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "tag": "vless-reality",
      "listen": "0.0.0.0",
      "port": ${XRAY_PORT},
      "protocol": "vless",
      "settings": {
        "decryption": "none",
        "clients": [
          {
            "id": "${XRAY_UUID}",
            "flow": "xtls-rprx-vision",
            "level": 0,
            "encryption": "none"
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "${SERVER_SNI}:443",
          "serverNames": [
            "${SERVER_SNI}"
          ],
          "privateKey": "${PRIVATE_KEY}",
          "shortIds": [
            "${XRAY_SHORT_ID}"
          ],
          "xver": 0
        }
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "freedom",
      "tag": "direct"
    },
    {
      "protocol": "blackhole",
      "tag": "reject"
    }
  ]
}
EOF

if systemctl list-unit-files | grep -q '^xray.service'; then
  systemctl restart xray
  systemctl enable xray
else
  echo "xray systemd unit not found; install script may have failed." >&2
  exit 1
fi

mkdir -p "$PROJECT_ROOT/client"

cat > "$PROJECT_ROOT/client/.env.server" <<EOF
SERVER_HOST_IP=${SERVER_HOST_IP}
XRAY_PORT=${XRAY_PORT}
SERVER_SNI=${SERVER_SNI}
XRAY_UUID=${XRAY_UUID}
XRAY_SHORT_ID=${XRAY_SHORT_ID}
XRAY_PUBLIC_KEY=${PUBLIC_KEY}
WHITELIST_URL=${WHITELIST_URL}
EOF

cat > "$PROJECT_ROOT/client/install_info.txt" <<EOF
VLESS server installed.
Server: ${SERVER_HOST_IP}:${XRAY_PORT}
UUID: ${XRAY_UUID}
SNI: ${SERVER_SNI}
Public Key: ${PUBLIC_KEY}
Short ID: ${XRAY_SHORT_ID}
Whitelist URL: ${WHITELIST_URL}
EOF

CLIENT_LINK="vless://${XRAY_UUID}@${SERVER_HOST_IP}:${XRAY_PORT}?security=reality&encryption=none&flow=xtls-rprx-vision&fp=chrome&pbk=${PUBLIC_KEY}&sni=${SERVER_SNI}&sid=${XRAY_SHORT_ID}&type=tcp#vless-reality"

cat <<EOF
VLESS server config: $CONFIG_PATH
Saved keys:
  Private Key: $PRIVATE_KEY
  Public  Key: $PUBLIC_KEY

Client link:
${CLIENT_LINK}

Saved install metadata in:
  $PROJECT_ROOT/client/install_info.txt
  $PROJECT_ROOT/client/.env.server
EOF
