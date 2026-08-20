#!/usr/bin/env bash
# Start Issue2Test Worker on the host JVM.
# Connects to the PostgreSQL instance started by ./scripts/up.sh.
# This script does not read .env, does not start a second database,
# does not mount docker.sock, and does not silently switch to FAKE.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="$ROOT/docker/compose.env"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yaml")
WORKER_PORT="${PATCHATLAS_WORKER_SERVER_PORT:-8081}"

refuse_fake() {
    echo "refusing: this script does not start a FAKE generator" >&2
    echo "this command does not read .env files and does not silently switch to FAKE" >&2
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

if [[ "${PATCHATLAS_GENERATOR_TYPE:-}" == "FAKE" ]]; then
    refuse_fake
    exit 2
fi

if missing_worker_requirements; then
    exit 2
fi

if [[ -f "$ROOT/.env" ]]; then
    echo "note: .env exists in the working tree but this script does not read it" >&2
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required; the sandbox runs on the host Docker engine" >&2
    exit 2
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose is required so this Worker can reuse the console PostgreSQL" >&2
    exit 2
fi

if ! "${COMPOSE[@]}" exec -T postgres pg_isready -U patchatlas -d patchatlas >/dev/null 2>&1; then
    echo "PostgreSQL from ./scripts/up.sh is not reachable" >&2
    echo "start the console first: ./scripts/up.sh" >&2
    echo "this script does not start a second database" >&2
    exit 2
fi

if ! (echo >/dev/tcp/127.0.0.1/5432) >/dev/null 2>&1; then
    echo "PostgreSQL is not published on 127.0.0.1:5432" >&2
    echo "the host Worker connects to the compose instance; start ./scripts/up.sh first" >&2
    echo "this script does not start a second database" >&2
    exit 2
fi

if [[ ! -d "${PATCHATLAS_WORKER_WORKSPACE_ROOT}" ]]; then
    mkdir -p "${PATCHATLAS_WORKER_WORKSPACE_ROOT}"
fi

if curl -sf "http://127.0.0.1:${WORKER_PORT}/api/v1/health" >/dev/null 2>&1; then
    echo "Worker already running on 127.0.0.1:${WORKER_PORT}"
    exit 0
fi

export SPRING_PROFILES_ACTIVE=persistence
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/patchatlas"
export SPRING_DATASOURCE_USERNAME=patchatlas
export SPRING_DATASOURCE_PASSWORD=patchatlas
export PATCHATLAS_WORKER_ENABLED=true
export PATCHATLAS_GENERATOR_TYPE=OPENAI
export SERVER_PORT="${WORKER_PORT}"

echo "starting host Worker on 127.0.0.1:${WORKER_PORT}"
echo "connecting to compose PostgreSQL at 127.0.0.1:5432"
echo "this command does not read .env files"

exec ./mvnw spring-boot:run -Dspring-boot.run.arguments="--patchatlas.worker.enabled=true --patchatlas.worker.workspace-root=${PATCHATLAS_WORKER_WORKSPACE_ROOT} --patchatlas.generator.type=OPENAI --server.port=${WORKER_PORT}"
