#!/usr/bin/env bash
# Local development runner.
#
# Starts:
#   - Jetty on :9000 hosting the Java function at /uat_generator/*
#   - Python http.server on :8000 hosting the static client
#
# Browse to http://localhost:8000/ for the UI. The client auto-detects
# localhost and talks to http://localhost:9000/uat_generator.
#
# Defaults to LLM_PROVIDER=mock so no API keys are needed. Set
# ANTHROPIC_API_KEY and unset LLM_PROVIDER (or set it to "claude") to call
# the real Claude API.
set -euo pipefail

cd "$(dirname "$0")"

export LLM_PROVIDER="${LLM_PROVIDER:-mock}"
echo "LLM_PROVIDER=$LLM_PROVIDER"

cleanup() {
  echo
  echo "Shutting down..."
  [[ -n "${STATIC_PID:-}" ]] && kill "$STATIC_PID" 2>/dev/null || true
  [[ -n "${JETTY_PID:-}" ]] && kill "$JETTY_PID"  2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Starting static client server on :8000 ..."
( cd client && python3 -m http.server 8000 ) &
STATIC_PID=$!

echo "Starting Jetty on :9000 ..."
( cd functions/uat_generator && mvn -q jetty:run ) &
JETTY_PID=$!

echo
echo "Web UI:     http://localhost:8000/"
echo "Function:   http://localhost:9000/uat_generator/health"
echo
echo "Ctrl+C to stop."
wait
