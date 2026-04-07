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

OUTPUT_PATH="${CLIENT_OUTPUT_PATH:-${PROJECT_ROOT}/client/config-split.json}"
TMP_CONFIG="$(mktemp)"
trap 'rm -f "$TMP_CONFIG"' EXIT

"$SCRIPT_DIR/make-client-config.sh" -o "$TMP_CONFIG" >/dev/null

mkdir -p "$(dirname "$OUTPUT_PATH")"
if [[ -f "$OUTPUT_PATH" ]] && cmp -s "$TMP_CONFIG" "$OUTPUT_PATH"; then
  echo "No changes. Config is up to date: $OUTPUT_PATH" >&2
  exit 0
fi

mv "$TMP_CONFIG" "$OUTPUT_PATH"
if [[ -f /var/run/xray/xray.pid ]] || systemctl is-active --quiet xray; then
  systemctl restart xray >/dev/null 2>&1 || true
  echo "Client config updated and xray restarted (if service exists)." >&2
else
  echo "Client config updated: $OUTPUT_PATH" >&2
fi
