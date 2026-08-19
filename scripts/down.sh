#!/usr/bin/env bash
# Stop the packaged console started by scripts/up.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

docker compose --env-file "$ROOT/docker/compose.env" -f "$ROOT/compose.yaml" down
