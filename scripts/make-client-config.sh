#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$PROJECT_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$PROJECT_ROOT/.env"
  set +a
fi

if [[ -f "$PROJECT_ROOT/client/.env.server" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$PROJECT_ROOT/client/.env.server"
  set +a
fi

if [[ -z "${WHITELIST_URL:-}" ]]; then
  WHITELIST_URL="https://raw.githubusercontent.com/kulikov0/whitelist-bypass/main/whitelist.txt"
fi

OUTPUT_PATH="${CLIENT_OUTPUT_PATH:-${PROJECT_ROOT}/client/config-split.json}"
VPS_HOST="${SERVER_HOST_IP:-${VPS_HOST:-}}"
XRAY_PORT="${XRAY_PORT:-443}"
XRAY_UUID="${XRAY_UUID:-}"
XRAY_PUBLIC_KEY="${XRAY_PUBLIC_KEY:-}"
XRAY_SHORT_ID="${XRAY_SHORT_ID:-}"
SERVER_SNI="${SERVER_SNI:-www.microsoft.com}"

if [[ -z "${VPS_HOST}" ]]; then
  echo "Set VPS_HOST or SERVER_HOST_IP in .env or client/.env.server" >&2
  exit 1
fi
if [[ -z "${XRAY_UUID}" || -z "${XRAY_PUBLIC_KEY}" || -z "${XRAY_SHORT_ID}" ]]; then
  echo "XRAY_UUID, XRAY_PUBLIC_KEY and XRAY_SHORT_ID are required." >&2
  exit 1
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|--out)
      OUTPUT_PATH="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [-o /path/to/config.json]" >&2
      exit 1
      ;;
  esac
done

mkdir -p "$(dirname "$OUTPUT_PATH")"

TMP_WHITELIST="$(mktemp)"
TMP_DOMAINS_JSON="$(mktemp)"
TMP_CONFIG="$(mktemp)"
trap 'rm -f "$TMP_WHITELIST" "$TMP_DOMAINS_JSON" "$TMP_CONFIG"' EXIT

echo "Downloading whitelist from $WHITELIST_URL..." >&2
curl -fsSL "$WHITELIST_URL" | tr -d '\r' > "$TMP_WHITELIST"

awk '
  BEGIN { OFS="\n" }
  /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
  {
    split($0, parts, ":")
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", parts[1])
    if (parts[1] != "" &&
        parts[1] !~ /^\./ &&
        parts[1] ~ /^([A-Za-z0-9.-]+\.)+[A-Za-z]{2,}$/) {
      print parts[1]
    }
  }
' "$TMP_WHITELIST" | sort -u > "$TMP_WHITELIST".clean

if [[ ! -s "$TMP_WHITELIST.clean" ]]; then
  echo "Whitelist is empty or invalid at $WHITELIST_URL" >&2
  exit 1
fi

jq -R -s 'split("\n") | map(select(length>0))' "$TMP_WHITELIST.clean" > "$TMP_DOMAINS_JSON"

cat > "$TMP_CONFIG" <<EOF
{
  "log": {
    "access": "",
    "error": "",
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "tag": "socks",
      "listen": "127.0.0.1",
      "port": 1080,
      "protocol": "socks",
      "settings": {
        "auth": "noauth",
        "udp": true,
        "ip": "127.0.0.1"
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls", "quic", "fakedns"]
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "vless",
      "tag": "proxy",
      "settings": {
        "vnext": [
          {
            "address": "${VPS_HOST}",
            "port": ${XRAY_PORT},
            "users": [
              {
                "id": "${XRAY_UUID}",
                "flow": "xtls-rprx-vision",
                "encryption": "none"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "serverName": "${SERVER_SNI}",
          "fingerprint": "chrome",
          "publicKey": "${XRAY_PUBLIC_KEY}",
          "shortId": "${XRAY_SHORT_ID}"
        }
      }
    },
    {
      "protocol": "freedom",
      "tag": "direct"
    }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      {
        "type": "field",
        "outboundTag": "direct",
        "domain": $(cat "$TMP_DOMAINS_JSON")
      },
      {
        "type": "field",
        "outboundTag": "proxy",
        "network": "tcp"
      },
      {
        "type": "field",
        "outboundTag": "proxy",
        "network": "udp"
      }
    ]
  }
}
EOF

mv "$TMP_CONFIG" "$OUTPUT_PATH"
echo "Saved config to: $OUTPUT_PATH" >&2
