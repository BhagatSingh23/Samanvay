#!/usr/bin/env bash
# ============================================================
# wait-for-health.sh — Polls GET /api/v1/health until UP
#
# Usage: ./scripts/wait-for-health.sh [URL] [TIMEOUT_SECONDS]
#   Default URL:     http://localhost:8080/api/v1/health
#   Default timeout: 120 seconds
# ============================================================

set -euo pipefail

URL="${1:-http://localhost:8080/api/v1/health}"
TIMEOUT="${2:-120}"
INTERVAL=3

echo "╔══════════════════════════════════════════════════════"
echo "║  Waiting for fabric-api to become healthy …"
echo "║  URL:     $URL"
echo "║  Timeout: ${TIMEOUT}s"
echo "╚══════════════════════════════════════════════════════"

elapsed=0

while [ "$elapsed" -lt "$TIMEOUT" ]; do
  # Try to get health endpoint
  status=$(curl -s -o /dev/null -w "%{http_code}" "$URL" 2>/dev/null || echo "000")

  if [ "$status" = "200" ]; then
    echo ""
    echo "✓ fabric-api is UP (HTTP $status) after ${elapsed}s"
    echo ""

    # Also verify the response body contains "UP" if possible
    body=$(curl -s "$URL" 2>/dev/null || echo "{}")
    echo "  Health response: $body"
    echo ""
    exit 0
  fi

  # Show progress
  printf "\r  ⏳ %ds / %ds — HTTP %s" "$elapsed" "$TIMEOUT" "$status"
  sleep "$INTERVAL"
  elapsed=$((elapsed + INTERVAL))
done

echo ""
echo "✗ Timed out after ${TIMEOUT}s waiting for $URL"
echo "  Last HTTP status: $status"
echo ""
echo "  Troubleshooting:"
echo "    docker compose logs fabric-api"
echo "    docker compose ps"
echo ""
exit 1
