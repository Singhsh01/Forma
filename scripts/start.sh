#!/usr/bin/env bash
# FORMA one-command start for macOS and Linux.
#
#   ./scripts/start.sh          build everything once, then serve app + API on http://localhost:8080
#   ./scripts/start.sh --dev    engine on :8080 plus the Vite dev server on :5173 (hot reload)
#
# Needs Java 21+ and Node 20+. The engine is built with the Maven Wrapper (downloads Maven and
# the plugins on first run); if Maven cannot download, it falls back to plain javac, which needs
# nothing beyond the JDK.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
PORT="${FORMA_PORT:-8080}"
MODE="${1:-}"

say() { printf '\033[1;33m[forma]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[forma]\033[0m %s\n' "$*" >&2; exit 1; }

command -v java >/dev/null || die "Java 21 or newer is required (java not found). Install a JDK 21, e.g. https://adoptium.net"
JV=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
[[ "${JV%%.*}" -ge 21 ]] 2>/dev/null || die "Java 21 or newer is required (found $JV)."
command -v node >/dev/null || die "Node.js 20 or newer is required (node not found). https://nodejs.org"
NV=$(node -p 'process.versions.node.split(".")[0]')
[[ "$NV" -ge 20 ]] || die "Node.js 20 or newer is required (found $(node -v))."

# ---- engine
CP=""
if [[ "${FORMA_SKIP_MAVEN:-}" != "1" ]] && ./mvnw -q -B -DskipTests package >/tmp/forma-maven.log 2>&1; then
  CP="server/target/forma-server.jar:engine/target/forma-engine.jar"
  say "engine built with Maven"
else
  [[ "${FORMA_SKIP_MAVEN:-}" == "1" ]] || say "Maven build failed or could not download (see /tmp/forma-maven.log); compiling with javac instead"
  ./scripts/javac-build.sh >/dev/null
  CP="build/classes"
  say "engine compiled with javac into build/classes"
fi

# ---- web
cd web
if [[ ! -d node_modules ]]; then
  say "installing web dependencies (npm ci)"
  npm ci --no-audit --no-fund
fi

if [[ "$MODE" == "--dev" ]]; then
  cd "$ROOT"
  say "starting the engine on http://localhost:$PORT (API) and the dev server on http://localhost:5173"
  java -cp "$CP" studio.forma.server.FormaServer --port "$PORT" &
  ENGINE=$!
  trap 'kill $ENGINE 2>/dev/null || true' EXIT INT TERM
  cd web
  npm run dev -- --port 5173
  exit 0
fi

say "building the web app"
npm run build >/dev/null
cd "$ROOT"
say "open http://localhost:$PORT  (Ctrl+C to stop)"
exec java -cp "$CP" studio.forma.server.FormaServer --port "$PORT" --web web/dist
