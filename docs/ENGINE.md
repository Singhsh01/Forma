# How the FORMA engine works

The engine (`engine/`, package `studio.forma.engine`) is plain Java 21 with no dependencies. Everything a design needs comes from four inputs: a **preset**, a **seed**, **parameters** and optional **rule overrides**. The same inputs always give the same design, byte for byte.

```
config ──► compose (massing) ──► realise in stages ──► validate ──► scene + replay + inspector
                 ▲                      │
                 └── MCMC refinement ◄──┘  (moves the massing, then re-realises it)
```

## 1. The grid

A design lives on a voxel grid of cells (1 cell = 1.5 m; a storey is 2 cells; x east, y up, z south; index `x + sx*(z + sz*y)`). Each cell holds one semantic state, not a colour:

`EMPTY AIR WALL FLOOR WINDOW COLUMN STAIR_XP/XN/ZP/ZN BRIDGE TERRACE GRASS VEG WATER TERRAIN ROOF GLASS LIGHT ARCH KEEP TRUNK CORE SUPPORT SHELF DOOR MARK_A..D CANOPY PATH`

`KEEP` reserves a void (an atrium, a nave) that later steps must not fill. `MARK_A..D` are temporary markers that rules consume (for example facade bays that later become wall or window). Each cell also stores the id of the **component** it belongs to (a tower, a bridge, a pod), which drives materials, selection in the viewer and the connectivity report.

Every write goes through a change journal, which feeds both the incremental rule matcher and the **history**: a sequence of sparse frames (cell index, new state) grouped by stage. The replay in the studio is this history, not an animation made afterwards; applying the frames in order reproduces the final grid exactly (tested). Small consecutive frames are coalesced to keep at most 720 frames.

## 2. Composition (massing)

`Preset.compose(params, rng)` builds a small architectural description: **volumes** (box, round or ring footprints with a base level, storeys, roof garden flag, courtyard size), **links** between volumes (sky bridges, walkways, garden bridges) and **voids**. This is the state MCMC refines. It is tiny compared with the grid, every edit to it has an exact reverse, and it can be realised again deterministically.

## 3. Realisation in stages

`Preset.realize(massing, ctx)` writes the grid in named stages. Each stage is timed, records a list of operations in plain language, and appears as a segment on the replay timeline. Typical stages: site, masses, circulation, rule-driven growth, validation, detailing, and (for the flagship) a refinement stage. Constructive stages use a small kit (`arch/Kit`): storeyed shells with bay rhythm, straight stair runs with solid masonry below, circulation cores, doors, bridge decks stamped along a line, rock spires.

## 4. Rewrite rules (MarkovJunior-style)

Rule programs are short texts. Each preset ships default programs (see them, and edit them, in the studio's Rules tab or with `POST /api/programs`).

```
sequence growth
  prl windows steps=1
    rule tall "1 1" -> "N N" p=0.72 sym=none     # a bay marker two cells tall becomes a tall window
  one roof-gardens steps=300
    rule grow "GT" -> "GG"                        # grass spreads across a terrace one cell at a time
```

* **Patterns.** Characters are cells along x, `/` separates rows along z, a space separates layers bottom to top. `"T E"` is a terrace with empty space above it. Each state has one character (`E` empty, `A` air, `W` wall, `F` floor, `N` window, `C` column, `x X z Z` stairs, `B` bridge, `T` terrace, `G` grass, `V` vegetation, `w` water, `R` terrain, `r` roof, `g` glass, `l` light, `a` arch, `K` keep, `t` trunk, `O` core, `P` support, `S` shelf, `D` door, `1-4` markers, `y` canopy, `p` path). In inputs `*` matches anything and union symbols match sets: `.` empty or air, `_` empty, air or keep, `#` anything massive, `^` anything walkable, `i` interior (air, floor, shelf), `o` empty or keep. In outputs `*` keeps the cell; unions are not allowed in outputs.
* **Symmetry.** `sym=none|mirror|rotate|full`. Rotation is about the vertical axis only, so rules never turn gravity sideways.
* **Nodes.**
  * `one` picks one random match per step and applies it (uses an incremental match cache driven by the change journal, so long growth runs stay fast);
  * `all` finds every match and applies a maximal non-overlapping set in seeded random order;
  * `prl` applies every match found at the start of the step independently with probability `p`;
  * `sequence` runs children in order, each until it can make no more progress;
  * `markov` repeatedly steps the first child that can make progress.
  * `steps=N` bounds a node; a global limit of 400,000 rule steps protects the server.
* **Errors** carry line numbers (`line 3: rule 'bad': every row needs 2 cells`). The parser caps a program at 200 rules and 20,000 characters.
* **Records.** Every rule's application count, first and last step, and a before/after sample of one application are recorded and shown in the inspector with small diagrams drawn from the actual cells.

## 5. Wave Function Collapse

`wfc/TileSet` and `wfc/WfcSolver` implement tiled WFC on a 3D lattice: tiles with six face sockets (two tiles may touch when the touching sockets are identical), optional rotated copies about y, lowest-entropy observation with seeded tie-breaking, weighted choice, arc-consistent propagation with a work queue, and **bounded restarts**: a contradiction restarts from the initial constraints with a forked seed, up to a fixed number of attempts, then reports failure. There is no unbounded backtracking.

WFC only guarantees local adjacency. Both worlds that use it check global properties separately:

* **Canal Town** solves a 2D plan (blocks, streets, turns, tees, crossings, plazas, canals, canal turns, bridges) with canal mouths fixed on the edge, then keeps the largest connected street network, turns stray fragments into courtyards, runs a 2D rule pass that opens enclosed blocks into gardens, and only raises houses on plots whose frontage is reachable.
* **The Escher Labyrinth** solves a 3D lattice where gravity is part of the adjacency: platforms, stairs and arcades must stand on a pillar, a block or an arcade roof, and pillars must carry something. Stairs and the headroom tile above them share a direction-specific socket. It solves several candidates and keeps the one whose socket graph reaches the most tiles from the entrance.

## 6. Validation

Every preset runs the same checks on the finished grid, plus its own:

* **Circulation** (hard): a walk over the cells from the entrance. A person stands in a standable cell (floor, path, terrace, bridge, stair...) and needs a non-solid cell above. Stairs are entered from their back at the same level and left at their front one level up; cores connect vertically. Every required component must be reached.
* **Occupancy** (hard): writes that would have overwritten another element are refused and counted.
* **Voids** (hard): reserved voids stay open to the sky.
* **Headroom** above outdoor circulation (hard).
* **Support** (heuristic): massive cells must rest on a load path; walls may cantilever, slabs and bridges may span a preset-specific number of cells from a supported cell; branching columns and corbelled vaults rest on the cell diagonally below. This is a span-limited heuristic, not structural analysis.
* **Daylight proxy** (heuristic): share of outdoor walking surface with open sky above.
* **Height and density** (information), plus preset checks such as "ravine left open", "dominant interior space", "plan solved by WFC", "pods seated", "apparent joins (not walkable)".

## 7. Refinement (Metropolis-Hastings)

`mcmc/MassingRefiner` proposes small edits to the massing: shift a volume by one cell, add or remove a storey, toggle a roof garden, widen or narrow a courtyard, toggle a link. Each move family has a fixed probability and every move has an exact reverse of equal probability, so the proposal is symmetric. A proposal that breaks a hard constraint (site bounds, separation, spans, preset rules) is rejected outright. Otherwise it is accepted with probability `min(1, exp(-ΔE / T))`, where `E` is the weighted sum of the preset's objectives (stated preferences such as daylight between towers, linked circulation, proportion, variety, greenery, density). At fixed `T` this samples massings with probability proportional to `exp(-E/T)` within the valid set (a unit test checks the empirical distribution against the exact Boltzmann distribution on a small state space). The annealing schedule lowers `T` over the run and is labelled as an optimiser.

The refined design is the lowest-energy state the chain visited by default, or its final state if you choose; the result reports both energies, the acceptance rate, uphill moves accepted, rejections per constraint, the energy trace and the last accepted moves, and the checks of the re-realised design.

## 8. Geometry for the viewer

`geometry/SceneBuilder` turns cells into instanced primitives: greedy 3D box merging per component for masses, 2D slab merging for floors, paving, decks and water, vertical runs for columns and trunks, tilted connectors for branching columns, windows with jambs, sills, lintels and frames, stairs, arches, foliage, lamps, bookshelves, parapets and railings along drops. Presets can add curved primitives (`GenContext.prim`) and mark components whose cell walls should not be meshed (`primOnly`): the Organic Habitat draws its pods, trunks, struts and walkway tubes this way while the voxels underneath carry the validation.

## 9. Determinism and safety

* All randomness comes from `core/Rng` (SplittableRandom). Each subsystem forks a stream by label (`rng.fork("compose")`, `fork("rules:growth")`, `fork("mcmc:<seed>")`), so adding randomness in one place does not shift another.
* Jobs never share mutable state; results do not depend on thread scheduling.
* Generation checks a cancellation token between and inside stages and inside rule loops; the server gives each job a deadline.
* Rule programs from users are size- and step-limited and validated before a job is queued.

## 10. Presets

Each preset (`presets/*Preset.java`) declares its parameters, rule programs, grid size, composition, stages, validation additions, refinement profile, camera and atmosphere (sky palette, clouds, preferred projection; purely visual). `PresetRegistry` lists them in display order. Adding a world means implementing `Preset` (usually by extending `BasePreset`) and registering it; the API, studio and tests pick it up automatically (the engine tests run every registered preset through determinism, hard checks, parameter effects and refinement constraints).
