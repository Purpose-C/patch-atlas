#!/usr/bin/env bash
# Start the packaged console: PostgreSQL + API + Vue.
# This script does not read .env, does not start Issue2Test Worker, and does not call a model.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="$ROOT/docker/compose.env"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml")

refuse_worker() {
    echo "refusing to start Worker in this compose stack" >&2
    echo "the one-command path starts the read console only (PostgreSQL + API + Vue)" >&2
    echo "sandbox execution uses the host Docker engine; this packaging does not mount it" >&2
    echo "this command does not read .env files" >&2
}

missing_worker_requirements() {
    local missing=()
    if [[ -z "${OPENAI_API_KEY:-}" ]]; then
        missing+=("OPENAI_API_KEY")
    fi
    if [[ -z "${PATCHATLAS_OPENAI_MODEL:-}" ]]; then
        missing+=("PATCHATLAS_OPENAI_MODEL")
    fi
    if [[ -z "${PATCHATLAS_WORKER_WORKSPACE_ROOT:-}" ]]; then
        missing+=("PATCHATLAS_WORKER_WORKSPACE_ROOT")
    fi
    if ((${#missing[@]} > 0)); then
        echo "refusing to start Worker: missing credentials or workspace" >&2
        local name
        for name in "${missing[@]}"; do
            echo "  missing: ${name}" >&2
        done
        echo "this command does not read .env files and does not silently switch to FAKE" >&2
        return 0
    fi
    return 1
}

if [[ "${1:-}" == "--worker" ]]; then
    if missing_worker_requirements; then
        exit 2
    fi
    refuse_worker
    exit 2
fi

if [[ "${PATCHATLAS_WORKER_ENABLED:-}" == "true" ]]; then
    echo "refusing: PATCHATLAS_WORKER_ENABLED=true is set" >&2
    echo "the one-command compose stack does not start Worker" >&2
    exit 2
fi

if [[ "${PATCHATLAS_GENERATOR_TYPE:-}" == "OPENAI" ]]; then
    echo "refusing: PATCHATLAS_GENERATOR_TYPE=OPENAI is set" >&2
    echo "the one-command compose stack uses FAKE and does not call a model" >&2
    echo "this command does not read .env files" >&2
    exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to start PostgreSQL, the API, and the Vue console" >&2
    exit 2
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose is required" >&2
    exit 2
fi

if [[ -f "$ROOT/.env" ]]; then
    echo "note: .env exists in the working tree but this script does not read it" >&2
fi

# Leftover containers with these names make `compose up` fail with
# "The container name is already in use". Remove them first so a second
# start succeeds. Do not remove named volumes.
for name in patchatlas-postgres-1 patchatlas-app-1 patchatlas-web-1; do
    if docker container inspect "$name" >/dev/null 2>&1; then
        echo "removing leftover container ${name} so compose can start" >&2
        docker rm -f "$name" >/dev/null
    fi
done

"${COMPOSE[@]}" up --build -d --wait --wait-timeout 600

echo "PatchAtlas console is up"
echo "  UI:   http://127.0.0.1:8080/runs"
echo "  API:  http://127.0.0.1:8080/api/v1/health"
echo "Worker is not started. Historical evaluation evidence is in benchmark-cases/, not this empty database."
