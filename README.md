# FORMA: Worlds by Rules

FORMA grows architecture from rules. A Java engine composes a massing from parameters and a seed, rasterises it into a voxel grid, lets MarkovJunior-style rewrite rules grow windows, gardens and details, fills tile lattices with Wave Function Collapse, walks the result to prove every space is reachable, and can refine the composition with a real Metropolis-Hastings sampler. A React and three.js studio shows the result as a lit diorama, lets you replay every recorded stage, read and edit the rules, compare refinements and export images, models and reproducible project files.

![FORMA in 14 seconds: live generation, growth replay, the seven worlds, isometric render modes, rules, checks and refinement](docs/video/forma-demo.gif)

Watch the [14-second demo (MP4)](docs/video/forma-demo-14s.mp4) or the [full feature tour (1:42)](docs/video/forma-feature-tour.mp4).

![The studio with the flagship design](docs/screenshots/studio-library-desktop.png)

## Quick start

You need **Java 21+** and **Node.js 20+**.

macOS / Linux:

```bash
./scripts/start.sh            # builds the engine and the web app, then serves both on http://localhost:8080
./scripts/start.sh --dev      # engine on :8080 + Vite dev server with hot reload on http://localhost:5173
```

Windows (PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start.ps1
powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -Dev
```

The start scripts build the engine with the Maven Wrapper (`mvnw`, which downloads Maven 3.9.11 on first use). If Maven cannot download anything, they fall back to compiling with plain `javac`, which needs nothing but the JDK. Set `FORMA_PORT` to use another port; set `FORMA_SKIP_MAVEN=1` to go straight to `javac`.

### Doing it by hand

```bash
./mvnw -DskipTests package                       # engine/target/forma-engine.jar, server/target/forma-server.jar
cd web && npm ci && npm run build && cd ..
java -cp server/target/forma-server.jar:engine/target/forma-engine.jar \
     studio.forma.server.FormaServer --port 8080 --web web/dist
```

(On Windows, separate the classpath with `;`.) Without Maven: `./scripts/javac-build.sh` compiles everything into `build/classes`.

### Command line

```bash
java -cp engine/target/forma-engine.jar studio.forma.engine.cli.FormaCli list
java -cp engine/target/forma-engine.jar studio.forma.engine.cli.FormaCli generate canal --seed 21 --param size=14 --out out/canal
java -cp engine/target/forma-engine.jar studio.forma.engine.cli.FormaCli config examples/escher-tall.json --out out/escher
java -cp engine/target/forma-engine.jar studio.forma.engine.cli.FormaCli refine library --seed 7 --iterations 2000 --keep best --out out/refined
```

Each run writes `scene.json`, `replay.json`, `inspector.json` and `config.json` and prints the constraint report.

## The seven worlds

| World | What makes it | Notes |
|---|---|---|
| **A. The Library Above the Clouds** (flagship) | Terraced reading rings around an open atrium, satellite towers, sky bridges, cloud platforms; rules for bays, bookshelves, roof gardens | Clouds are presentation only; grounded mode checks a support heuristic, fantasy mode lets islands float and says so |
| **B. Cliffside City** | Stepped benches with retaining walls, houses, switchback stairs, a ravine crossed only by bridges | Ravine kept open is a hard check |
| **C. Hanging Gardens** | Cantilevered stacks around a courtyard; rules grow planters, vines and waterfalls downward | Every overhang is within the support span limit |
| **D. Cathedral of Light** | Nave, aisles, transept, apse, towers, arcades; a rule grows branching columns into the vault | The nave must stay one uninterrupted interior |
| **E. Canal Town** | 2D tile WFC plan (canals, streets, bridges, plazas, blocks), a 2D rule pass for courtyards, then extrusion | WFC is local; connectivity is repaired and checked by traversal |
| **F. Organic Habitat** | Living trunks with seed-shaped pods on branching struts, walkway tubes between trunks | Curved forms are separate primitives drawn over the voxels; validation runs on the voxels |
| **G. Escher-Inspired Labyrinth** | 3D tile WFC with gravity in the adjacency; opens in true isometric view | An optical illusion, not traversable impossible space: apparent joins are detected and reported as not walkable |

![Gallery](docs/screenshots/landing-gallery-desktop.png)

## The Markov chain: how FORMA refines a design

Yes, there is a real Markov chain in FORMA, and it is the part that turns one generated design into a family of better ones. Refinement is **Markov chain Monte Carlo (MCMC)** with the **Metropolis-Hastings** rule. Each step depends only on the current design, never on how the chain got there, which is what makes it a Markov chain.

(FORMA also borrows the name from MarkovJunior: a `markov` node in the rule language keeps applying the first rule that can still change the grid. That is a rewrite-rule control structure, not a probabilistic chain. The sampler described here is the probabilistic one.)

### What the chain walks over

The chain does not move voxels. It moves the **massing**: the small description each world composes before anything is built. That means volume positions, storey counts, roof gardens, courtyard sizes and which links (bridges, walkways) exist. The state is tiny, every edit can be undone exactly, and any state can be turned back into a full building, which is why the chain can explore quickly.

### One step of the chain

1. **Propose** a small edit at random. Each kind of edit has a fixed probability:

   | Move | Example from the flagship |
   |---|---|
   | Shift a volume one cell | "shift Tower 2 east" |
   | Add or remove a storey | "raise Tower 3 to 9 storeys" |
   | Toggle a roof garden | "add roof garden to Tower 1" |
   | Widen or narrow a courtyard | "widen the open court of the atrium" |
   | Toggle a link | "remove sky bridge between Tower 1 and Tower 4" |

   Every move has an exact reverse that is just as likely (east and west, raise and lower, on and off), so the proposal is symmetric and the Hastings correction cancels.
2. **Check hard constraints.** If the edit breaks a rule of the world (stays on site, towers apart, bridge spans within limits, platforms reachable from the pinnacle), it is rejected immediately and the chain stays where it is.
3. **Score it.** The energy `E` is a weighted sum of the world's objectives, each a penalty that is 0 when ideal: daylight between towers, linked circulation, slender proportions, a lively skyline, greenery, density and so on. You set the weights with sliders in the Refine tab. These are stated design preferences, not a measure of beauty.
4. **Accept or reject** with the Metropolis rule:

   ```
   accept with probability  min(1, exp(-ΔE / T))
   ```

   An improvement (`ΔE < 0`) is always kept. A worse design is sometimes kept, more often when the temperature `T` is high. Those uphill steps let the chain escape a local optimum instead of getting stuck on the first decent layout.

At a fixed temperature, the chain spends its time in each valid design in proportion to `exp(-E/T)` (the Boltzmann distribution). This is checked, not assumed: `metropolisSamplesTheBoltzmannDistribution` runs the sampler on a small state space and compares the visit counts with the exact distribution. A second test checks that uphill moves are accepted at the right rate.

### Sampling or optimising

* **MCMC at fixed T** samples good designs and keeps exploring. This is the default.
* **Simulated annealing** lowers `T` from a start value to an end value over the run. It behaves like an optimiser and is labelled as one in the UI.
* **Keep: lowest energy visited / final state.** A sampler wanders, so its last design can be worse than the best one it passed. By default FORMA re-builds the lowest-energy design it saw; you can switch to the final state.

### A real run

The flagship library (seed 7), 2,000 iterations at `T = 0.25`, sampler seed 1:

* 1,243 of 2,000 proposals accepted, 504 of them uphill
* energy 4.924 at the start, 1.850 for the best design visited, 3.203 where the chain ended
* about 0.1 s in total; the sampler itself is a small fraction of that, most of the time goes into re-building the chosen design

The refined design is re-built through the same pipeline and runs through every constraint check again, so a refined building is held to the same standard as a generated one. The studio shows the energy trace, the per-objective before/after table, the last accepted moves with their `ΔE` and acceptance probability, and a before/after compare of the two buildings.

### Reproducible

The chain's randomness comes only from the design seed and the sampler seed, so the same design, settings and sampler seed always give the same chain, step for step. Project files store the refinement settings along with the design.

### Try it

* In the studio: open the **Refine** tab, adjust the weights and temperature, then **Run refinement**. Press **B** to flip between before and after.
* From the command line:

  ```bash
  java -cp engine/target/forma-engine.jar studio.forma.engine.cli.FormaCli refine library \
       --seed 7 --iterations 2000 --temperature 0.25 --mcmc-seed 1 --keep best --out out/refined
  # add --anneal 0.02 to anneal from 0.25 down to 0.02
  ```

### Where it lives in the code

| File | Role |
|---|---|
| `engine/.../mcmc/MetropolisSampler.java` | Generic Metropolis-Hastings sampler: proposals with forward/reverse log probabilities, hard constraints, temperature schedule, trace |
| `engine/.../mcmc/MassingRefiner.java` | The move families above, applied to a massing |
| `engine/.../mcmc/RefineProfile.java`, `Objectives.java` | What each world lets the chain change, its objectives and hard constraints |
| `engine/.../mcmc/Refinement.java` | Runs a refinement, re-builds the kept design and reports the result (shared by the CLI and the server) |
| `engine/.../presets/*Preset.java` (`refineProfile`) | Per-world choices: for example the Escher labyrinth's chain moves anchor platforms to create more optical joins in the isometric view |

## What is in the box

```
engine/     Java 21 engine: rules, WFC, MCMC, validation, presets, scene builder, CLI (no runtime dependencies)
server/     HTTP API on the JDK's built-in HttpServer: bounded job queue, SSE progress, results, static hosting
web/        React 19 + TypeScript + Vite + three.js (react-three-fiber) studio, landing page and "How it grows"
scripts/    start scripts, javac build, example and image regeneration, measurements
docs/       ENGINE.md, API.md, screenshots
examples/   configs you can run with the CLI or import in the studio
```

* [docs/ENGINE.md](docs/ENGINE.md): how generation works, the rule language, WFC, refinement, validation, determinism.
* [docs/API.md](docs/API.md): the HTTP API.
* [DESIGN.md](DESIGN.md): the visual system and the design refinement log.
* [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Tests

```bash
./mvnw test                     # JUnit 5: engine + server (54 tests)
./scripts/javac-build.sh --test # the same suite without Maven, using a JUnit console jar (JUNIT_JAR=...)
cd web && npm test              # Vitest: decoding, replay, project files, API errors (14 tests)
cd web && npx playwright test   # end to end against the real engine: 17 desktop flows + 1 phone layout
```

The Playwright config starts the engine (`scripts/serve-engine.sh`) and the dev server itself unless they are already running. The end-to-end tests drive the real Java engine; only two tests inject failures on purpose (an engine error, an unreachable engine) and one holds the progress stream open to test cancellation.

What the engine tests cover: every preset is deterministic, passes its hard checks on several seeds, every parameter changes the geometry, composed massings lie inside the refinement's valid state space, MCMC samples the Boltzmann distribution on a small state space, replay reconstructs the final grid exactly, rule parsing errors carry line numbers, WFC respects sockets and restarts deterministically. Server tests cover determinism through the job manager, 429 on a full queue, cancellation, timeouts, refinement, every HTTP endpoint, SSE, errors as JSON, SPA fallback and path traversal.

## Measured performance

Measured with `node scripts/measure.mjs` against a warm engine (one warm-up, median of five jobs, default parameters) in a 2-vCPU cloud container (Intel Xeon @ 2.10 GHz, OpenJDK 21.0.12, 7 GB RAM). Times are the server-side elapsed time of a whole job, including serialising the scene. Your machine will differ; rerun the script.

| World | Generate (ms) | Instances | Scene JSON | Replay frames |
|---|---|---|---|---|
| Library | 100 | 14,810 | 738 KB | 84 |
| Cliffside | 54 | 3,348 | 171 KB | 60 |
| Gardens | 51 | 4,668 | 236 KB | 70 |
| Cathedral | 39 | 3,511 | 177 KB | 78 |
| Canal | 20 | 2,018 | 111 KB | 127 |
| Organic | 21 | 1,493 | 78 KB | 83 |
| Escher | 57 | 393 | 22 KB | 17 |

Refining the flagship with 2,000 MCMC iterations took 114 ms per job (11 ms in the sampler, the rest re-realising the design); 8,000 iterations took 145 ms. Cold runs from the CLI (a fresh JVM each time) are several times slower: about 0.5 to 0.7 s for the library. The production web bundle is about 1.7 MB of JavaScript before compression (about 530 KB gzipped), most of it three.js and react-three-fiber; the 3D viewer is the bulk of it and loads with the first page.

Rendering speed depends on the GPU. In this container the browser only had software WebGL (SwiftShader), where the studio is usable but slow; frame rates on real hardware were not measured.

## Limitations, honestly

* **No Spring Boot.** Maven Central was unreachable from the environment FORMA was built in, so the server uses the JDK's built-in `com.sun.net.httpserver` and has no third-party runtime dependencies. The API is small and the server is about 750 lines in three classes; moving it to Spring Boot is mechanical if you want that.
* **Maven builds were verified offline only.** The POMs compiled and the full test suite passed under Maven using locally available plugin versions; the newer plugin versions pinned in the POMs (compiler 3.14.0, surefire 3.5.3, JUnit 5.12.2) could not be downloaded there. The `javac` path is what the start scripts fall back to and is fully verified.
* **The Windows start script was tested with PowerShell 7 on Linux**, not on Windows itself.
* **Architecture is heuristic.** Support is a span-limited load-path check, daylight is "open sky above outdoor walkways", circulation is a graph walk over cells. Nothing here is structural analysis or building-code compliance.
* **Refinement objectives are stated preferences**, not measures of beauty. The sampler realises the lowest-energy state it visited by default (switchable to the chain's final state).
* **ConvChain is not used.** FORMA's MCMC samples architectural massings (positions, storeys, gardens, links) with symmetric reversible moves; it does not do ConvChain's pattern-based texture synthesis.
* **The Escher world is an illusion of the view.** Its geometry is ordinary; fragments no stair reaches are drawn as ornament and labelled as such (in the shipped example 41% of walkable cells are reachable).
* **Clouds, skies, porthole windows and walkway tubes are presentation.** The voxel grid is the design of record; curved primitives in the Organic Habitat are drawn over voxels that carry the validation.
* **GLB export bakes the rendered meshes**; ink and blueprint edge rendering is screen-space, so those modes export as PNG only. There is no SVG export.
* **Saved projects live in the browser's local storage.** Project files (`.forma.json`) are the portable form.

## License

FORMA's own code: MIT (see `LICENSE`). Third-party components keep their licenses; see `THIRD_PARTY_NOTICES.md`.
