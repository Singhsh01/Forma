#!/usr/bin/env bash
# Builds the engine and server with plain javac (no Maven, no downloads) into build/.
# Optional: runs the JUnit suite when a JUnit Platform console jar is available
# (set JUNIT_JAR, or it is looked up in /usr/share/java).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build
rm -rf "$OUT/classes" "$OUT/test-classes"
mkdir -p "$OUT/classes" "$OUT/test-classes"
javac --release 21 -encoding UTF-8 -d "$OUT/classes" $(find engine/src/main/java server/src/main/java -name '*.java')
echo "compiled engine + server into $OUT/classes"
if [[ "${1:-}" == "--test" ]]; then
  JUNIT_JAR="${JUNIT_JAR:-/usr/share/java/junit-platform-console-standalone.jar}"
  if [[ ! -f "$JUNIT_JAR" ]]; then echo "JUnit console jar not found (set JUNIT_JAR)"; exit 2; fi
  javac --release 21 -encoding UTF-8 -cp "$OUT/classes:$JUNIT_JAR" -d "$OUT/test-classes" $(find engine/src/test/java server/src/test/java -name '*.java')
  java -jar "$JUNIT_JAR" execute --class-path "$OUT/classes:$OUT/test-classes" --scan-class-path --disable-banner --details=summary "${@:2}"
fi
