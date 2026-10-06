#!/usr/bin/env bash
# Starts the Java engine for development and end-to-end tests: uses the Maven-built jar when
# present, otherwise compiles with plain javac (no downloads needed).
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${FORMA_PORT:-8080}"
if [[ -f server/target/forma-server.jar && -f engine/target/forma-engine.jar ]]; then
  exec java -cp "server/target/forma-server.jar:engine/target/forma-engine.jar" studio.forma.server.FormaServer --port "$PORT" "$@"
fi
./scripts/javac-build.sh >/dev/null
exec java -cp build/classes studio.forma.server.FormaServer --port "$PORT" "$@"
