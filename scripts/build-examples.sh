#!/usr/bin/env bash
# Regenerates the precomputed example designs shipped with the web app (web/public/scenes/*)
# with the Java CLI. Every file is real engine output for the seed listed below; nothing is
# hand-edited. Run after changing the engine. Thumbnails are captured separately by
# scripts/capture-images.mjs from these scenes.
set -euo pipefail
cd "$(dirname "$0")/.."

# a plain javac build is fast and needs no downloads, and is always current with the sources
./scripts/javac-build.sh >/dev/null
CP=build/classes

run() { java -cp "$CP" studio.forma.engine.cli.FormaCli "$@"; }

# preset:seed pairs used for the gallery
EXAMPLES="library:7 cliffside:3 gardens:4 cathedral:2 canal:5 organic:3 escher:1"
for ex in $EXAMPLES; do
  preset=${ex%%:*}
  seed=${ex##*:}
  echo "== $preset (seed $seed)"
  run generate "$preset" --seed "$seed" --out "web/public/scenes/$preset" | grep -vE "^\s+\[" || true
done

# the flagship ships with a real MCMC refinement and its re-realised design
echo "== library refinement"
run refine library --seed 7 --iterations 2000 --temperature 0.25 --mcmc-seed 1 --keep best --out web/public/scenes/library
