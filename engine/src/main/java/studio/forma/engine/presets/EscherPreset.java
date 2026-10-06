package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
import studio.forma.engine.arch.ConstraintReport.Status;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.Massing.Shape;
import studio.forma.engine.arch.Massing.Volume;
import studio.forma.engine.arch.Walk;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components.Material;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;
import studio.forma.engine.mcmc.Objectives;
import studio.forma.engine.mcmc.RefineProfile;
import studio.forma.engine.wfc.TileSet;
import studio.forma.engine.wfc.WfcSolver;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * G. Escher-Inspired Labyrinth. A 3D tile WFC fills a lattice with platforms, stairs, arcades and
 * pillars. Gravity is part of the adjacency: every platform, stair and arcade must stand on a
 * pillar, a block or an arcade roof, and every pillar must carry something.
 *
 * <p>The impossible look is an optical effect of the isometric view: two platforms that sit
 * {@code k} levels apart along the view axis (1,1,1) project onto neighbouring screen positions
 * and appear to continue into each other. The preset finds and reports every such apparent join,
 * and states plainly that they are not walkable. Walkable circulation is validated on the voxels.
 */
public final class EscherPreset extends BasePreset {
    static final int G = 1;   // lattice level 0 walks at this height
    static final int T = 3;   // a tile is 3 x 3 x 3 cells (stairs rise one cell per cell)
    static final int OFF = 3;

    @Override public String id() { return "escher"; }
    @Override public String title() { return "Escher-Inspired Labyrinth"; }
    @Override public String summary() {
        return "Interlocking stairs, platforms and arcades whose isometric view suggests impossible connections.";
    }

    @Override public List<String> notes() {
        return List.of("This is an optical illusion, not a traversable impossible space: the geometry is ordinary and consistent.",
            "Apparent joins are platforms that line up along the isometric view axis. They are detected and reported; none is walkable.",
            "Gravity is in the tile adjacency: platforms, stairs and arcades must rest on a pillar, a block or an arcade roof.",
            "View it in the isometric projection (the default for this world) to see the effect.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("size", "Lattice size", "Tiles along each side.", 4, 8, 6, "massing"),
            ParamSpec.integer("levels", "Levels", "Tile levels stacked up.", 3, 6, 5, "massing"),
            ParamSpec.real("stairs", "Stairs", "Weight of stair tiles, and how strongly stair-rich solutions are preferred.", 0.2, 2, 0.1, 1.0, "circulation"),
            ParamSpec.real("arcades", "Arcades", "Weight of covered arcade tiles.", 0, 2, 0.1, 0.8, "massing"),
            ParamSpec.real("openness", "Openness", "Weight of empty air between the structures.", 0.4, 3, 0.1, 1.4, "massing"),
            ParamSpec.integer("anchors", "Anchor platforms", "Platforms the plan must contain; refinement moves them to strengthen the illusion.", 2, 6, 4, "massing"),
            ParamSpec.real("ornament", "Ornament", "Finials, urns and lanterns on the platforms.", 0, 1, 0.05, 0.5, "detail"));
    }

    @Override public List<String> programIds() {
        return List.of("detailing");
    }

    @Override public String defaultProgram(String id, Params p) {
        double orn = p.get("ornament");
        if (!id.equals("detailing")) throw new IllegalArgumentException("unknown program " + id);
        return String.format(Locale.ROOT, """
            sequence detailing
              prl finials steps=1
                rule finial "T E E" -> "* V *" p=%.3f sym=none   # potted trees on platform tiles
              prl lanterns steps=1
                rule lantern "T E" -> "* l" p=%.3f sym=none
            """, 0.01 + 0.05 * orn, 0.01 + 0.04 * orn);
    }

    @Override public int[] gridSize(Params p) {
        int n = p.i("size");
        return new int[]{n * T + 2 * OFF, G + p.i("levels") * T + 4, n * T + 2 * OFF};
    }

    @Override public double[] camera(Params p) {
        // true isometric: looking down the (1,1,1) axis
        return new double[]{0, p.i("levels") * T / 2.0, 0, 90, 45, 35.264};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("paper", false, "orthographic", 0);
    }

    // ---- tiles ------------------------------------------------------------------------------

    static final String[] DIRS = {"E", "W", "S", "N"};   // stair rising toward +x, -x, +z, -z

    static TileSet tiles(Params p) {
        double ws = p.get("stairs"), wa = p.get("arcades"), wo = p.get("openness");
        double flat = 1 / Math.sqrt(ws);   // more stairs means relatively fewer flat platforms
        TileSet ts = new TileSet()
            .add("Air", 3.0 * wo, "n", "n", "o", "o", "n", "n", false)
            .add("Pillar", 0.7, "n", "n", "s", "s", "n", "n", false)
            .add("Block", 0.25, "n", "n", "s", "s", "n", "n", false)
            .add("WalkEnd", 0.25 * flat, "f", "n", "o", "s", "n", "n", true)
            .add("WalkStraight", 0.9 * flat, "f", "f", "o", "s", "n", "n", true)
            .add("WalkTurn", 0.8 * flat, "f", "n", "o", "s", "f", "n", true)
            .add("WalkTee", 0.45 * flat, "f", "f", "o", "s", "f", "n", true)
            .add("WalkCross", 0.2 * flat, "f", "f", "o", "s", "f", "f", false)
            .add("Arcade", Math.max(0.001, 0.6 * wa), "f", "f", "s", "s", "n", "n", true);
        // stairs and the headroom tile above them carry a direction-specific vertical socket, so a
        // stair can only be capped by the head tile of its own direction
        for (int d = 0; d < 4; d++) {
            String[] st = {"n", "n", "t" + DIRS[d], "s", "n", "n"};
            String[] hd = {"n", "n", "o", "t" + DIRS[d], "n", "n"};
            int lowFace = switch (d) { case 0 -> TileSet.NX; case 1 -> TileSet.PX; case 2 -> TileSet.NZ; default -> TileSet.PZ; };
            int highFace = TileSet.opposite(lowFace);
            st[lowFace] = "f";
            hd[highFace] = "f";
            ts.add("Stair" + DIRS[d], 0.5 * Math.sqrt(ws), st[0], st[1], st[2], st[3], st[4], st[5], false);
            ts.add("Head" + DIRS[d], 1.0, hd[0], hd[1], hd[2], hd[3], hd[4], hd[5], false);
        }
        return ts;
    }

    static boolean isPlatform(String base) {
        return base.startsWith("Walk") || base.equals("Arcade");
    }

    record Plan(int n, int levels, String[] base, int attempts, String failure, boolean anchorsDropped, int candidates, int reachable) {
        String at(int x, int y, int z) {
            return base[x + n * (z + n * y)];
        }
    }

    /** Lattice position (x, level, z) of an anchor volume. */
    static int[] anchorTile(Volume v, int n, int levels) {
        int x = Math.max(0, Math.min(n - 1, Math.floorDiv(v.cx() - OFF, T)));
        int z = Math.max(0, Math.min(n - 1, Math.floorDiv(v.cz() - OFF, T)));
        int y = Math.max(0, Math.min(levels - 1, v.storeys() - 1));
        return new int[]{x, y, z};
    }

    static final int CANDIDATES = 20;

    /**
     * Solves the lattice several times with forked seeds and keeps the solution whose socket graph
     * reaches the most tiles from the entrance platform (WFC alone says nothing about global
     * connectivity). If no solution honours the anchor platforms, the anchors are relaxed.
     */
    static Plan solve(Params p, Massing m, Rng rng) {
        int n = p.i("size"), L = p.i("levels");
        TileSet ts = tiles(p);
        long platform = 0;
        for (int i = 0; i < ts.size(); i++) if (isPlatform(ts.tile(i).base())) platform |= 1L << i;
        int attempts = 0;
        String failure = null;
        for (int pass = 0; pass < 2; pass++) {
            WfcSolver s = new WfcSolver(ts, n, L, n, null, false);
            for (int y = 0; y < L; y++)
                for (int i = 0; i < n; i++) {
                    s.restrict(0, y, i, ts.maskWithSocket(TileSet.NX, "n"));
                    s.restrict(n - 1, y, i, ts.maskWithSocket(TileSet.PX, "n"));
                    s.restrict(i, y, 0, ts.maskWithSocket(TileSet.NZ, "n"));
                    s.restrict(i, y, n - 1, ts.maskWithSocket(TileSet.PZ, "n"));
                }
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++) s.restrict(x, L - 1, z, ts.maskWithSocket(TileSet.PY, "o"));
            if (pass == 0)
                for (Volume v : m.volumes()) {
                    int[] a = anchorTile(v, n, L);
                    s.restrict(a[0], a[1], a[2], platform);
                }
            String[] best = null;
            double bestScore = -1;
            int solved = 0;
            for (int c = 0; c < CANDIDATES; c++) {
                WfcSolver.Result r = s.solve(rng.fork("pass" + pass + "-candidate" + c), 12, studio.forma.engine.core.Cancellation.none());
                attempts += r.attempts();
                if (!r.success()) {
                    failure = r.failure();
                    continue;
                }
                solved++;
                String[] base = new String[r.tiles().length];
                for (int i = 0; i < base.length; i++) base[i] = ts.tile(r.tiles()[i]).base();
                double score = reachableTiles(base, ts, r.tiles(), n, L, p.get("stairs"));
                if (score > bestScore) {
                    bestScore = score;
                    best = base;
                }
            }
            if (best != null) return new Plan(n, L, best, attempts, null, pass == 1, solved, (int) Math.round(bestScore));
        }
        String[] base = new String[n * n * L];
        java.util.Arrays.fill(base, "Air");
        return new Plan(n, L, base, attempts, failure, true, 0, 0);
    }

    /**
     * Score of a solution: tiles reachable from the entrance platform through matching "f" faces
     * and stair/head pairs, with each reachable stair counting {@code 1 + stairBonus}.
     */
    static double reachableTiles(String[] base, TileSet ts, int[] tile, int n, int L, double stairBonus) {
        int start = n / 2 + n * ((n - 1) + n * 0);
        boolean[] seen = new boolean[base.length];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        seen[start] = true;
        q.add(start);
        double count = 0;
        while (!q.isEmpty()) {
            int i = q.poll();
            count += base[i].startsWith("Stair") ? 1 + stairBonus : 1;
            int x = i % n, z = (i / n) % n, y = i / (n * n);
            String[] so = ts.tile(tile[i]).sockets();
            for (int d = 0; d < 6; d++) {
                int ax = x + TileSet.DX[d], ay = y + TileSet.DY[d], az = z + TileSet.DZ[d];
                if (ax < 0 || ay < 0 || az < 0 || ax >= n || ay >= L || az >= n) continue;
                int j = ax + n * (az + n * ay);
                if (seen[j]) continue;
                String[] o = ts.tile(tile[j]).sockets();
                boolean link = d < 2 || d > 3 ? so[d].equals("f") && o[TileSet.opposite(d)].equals("f")
                    : so[d].startsWith("t") && so[d].equals(o[TileSet.opposite(d)]);
                if (!link) continue;
                seen[j] = true;
                q.add(j);
            }
        }
        return count;
    }

    // ---- composition ------------------------------------------------------------------------

    @Override
    public Massing compose(Params p, Rng rng) {
        int[] size = gridSize(p);
        int n = p.i("size"), L = p.i("levels");
        List<Volume> vs = new ArrayList<>();
        // the entrance platform sits at ground level in the middle of the south edge
        vs.add(anchor("Entrance platform", n / 2, 0, n - 1, false));
        java.util.Set<Long> used = new java.util.HashSet<>();
        used.add(key(n / 2, 0, n - 1));
        int k = p.i("anchors");
        for (int i = 0; i < k * 10 && vs.size() < k + 1; i++) {
            int x = 1 + rng.nextInt(Math.max(1, n - 2)), z = 1 + rng.nextInt(Math.max(1, n - 2)), y = rng.nextInt(L);
            if (!used.add(key(x, y, z))) continue;
            vs.add(anchor("Anchor " + vs.size(), x, y, z, true));
        }
        return new Massing(size[0], size[1], size[2], G, vs, List.of(), List.of());
    }

    static long key(int x, int y, int z) {
        return ((long) x * 1000 + y) * 1000 + z;
    }

    static Volume anchor(String name, int x, int y, int z, boolean mobile) {
        return new Volume(name, "anchor", Shape.BOX, OFF + x * T + 1, OFF + z * T + 1, T, T, G + y * T, y + 1, false, 0, mobile, 0, false);
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        ctx.expectStages(6);
        Plan[] plan = new Plan[1];
        int[] main = new int[1];
        int[] fragmentComp = new int[1];
        int[] apparent = new int[1];
        List<String> joinExamples = new ArrayList<>();

        ctx.stage("plan", "3D tile WFC", "wfc",
            "Fill the lattice with platforms, stairs, arcades and pillars by Wave Function Collapse; gravity is part of the adjacency.", () -> {
                plan[0] = solve(p, m, ctx.rng.fork("wfc"));
                Plan pl = plan[0];
                int platforms = 0, stairs = 0;
                for (String b : pl.base()) {
                    if (isPlatform(b)) platforms++;
                    if (b.startsWith("Stair")) stairs++;
                }
                ctx.op("WFC collapsed a " + pl.n() + " x " + pl.levels() + " x " + pl.n() + " lattice in " + pl.attempts() + " attempt(s)"
                    + (pl.failure() != null ? " (failed: " + pl.failure() + ")" : pl.anchorsDropped() ? " after relaxing the anchor platforms" : ""));
                ctx.op(platforms + " platform tiles, " + stairs + " stair tiles; kept the best of " + pl.candidates() + " solutions, whose socket graph scores " + pl.reachable() + " (reachable tiles, stairs weighted)");
                int plinth = ctx.comps.add("Plinth", "terrain", Material.MARBLE);
                for (int z = 0; z < g.sz; z++)
                    for (int x = 0; x < g.sx; x++)
                        for (int y = 0; y < G; y++) g.set(x, y, z, Cell.TERRAIN, plinth);
            });

        ctx.stage("masses", "Pillars, blocks and arcades", "constructive",
            "Raise the load-bearing tiles: pillars, solid blocks and arcade frames.", () -> {
                Plan pl = plan[0];
                int pillars = ctx.comps.add("Pillars", "column", Material.MARBLE);
                int blocks = ctx.comps.add("Blocks", "wall", Material.SANDSTONE);
                ctx.comps.setRoof(blocks, Material.MARBLE);
                main[0] = ctx.comps.add("Labyrinth walks", "path", Material.PLASTER, true, false);
                int count = 0;
                for (int y = 0; y < pl.levels(); y++)
                    for (int z = 0; z < pl.n(); z++)
                        for (int x = 0; x < pl.n(); x++) {
                            String b = pl.at(x, y, z);
                            int x0 = OFF + x * T, y0 = G + y * T, z0 = OFF + z * T;
                            switch (b) {
                                case "Pillar" -> {
                                    for (int k = 0; k < T; k++) g.set(x0 + 1, y0 + k, z0 + 1, Cell.COLUMN, pillars);
                                    count++;
                                }
                                case "Block" -> {
                                    ctx.kit.box(x0, y0, z0, x0 + T, y0 + T, z0 + T, Cell.WALL, blocks);
                                    count++;
                                }
                                case "Arcade" -> {
                                    for (int cz = 0; cz < T; cz += 2)
                                        for (int cx = 0; cx < T; cx += 2) g.set(x0 + cx, y0 + 1, z0 + cz, Cell.COLUMN, pillars);
                                    for (int cz = 0; cz < T; cz++)
                                        for (int cx = 0; cx < T; cx++) g.set(x0 + cx, y0 + 2, z0 + cz, Cell.ROOF, blocks);
                                    count++;
                                }
                                default -> {}
                            }
                        }
                ctx.op(count + " load-bearing tiles");
            });

        ctx.stage("circulation", "Platforms and stairs", "constructive",
            "Lay the platforms and cut the stairs; each stair rises one level across one tile onto the platform beyond.", () -> {
                Plan pl = plan[0];
                int n = 0;
                for (int y = 0; y < pl.levels(); y++)
                    for (int z = 0; z < pl.n(); z++)
                        for (int x = 0; x < pl.n(); x++) {
                            String b = pl.at(x, y, z);
                            int x0 = OFF + x * T, y0 = G + y * T, z0 = OFF + z * T;
                            if (isPlatform(b)) {
                                for (int cz = 0; cz < T; cz++)
                                    for (int cx = 0; cx < T; cx++) g.set(x0 + cx, y0, z0 + cz, Cell.TERRACE, main[0]);
                                n++;
                            } else if (b.startsWith("Stair")) {
                                int d = switch (b.substring(5)) { case "E" -> 0; case "W" -> 1; case "S" -> 2; default -> 3; };
                                for (int w = 0; w < T; w++) {
                                    int sx = d == 0 ? x0 : d == 1 ? x0 + T - 1 : x0 + w;
                                    int sz = d == 2 ? z0 : d == 3 ? z0 + T - 1 : z0 + w;
                                    ctx.kit.stairRun(sx, y0, sz, d, T, main[0], true, y0);
                                }
                                n++;
                            }
                        }
                ctx.entrance(OFF + (pl.n() / 2) * T + 1, G, OFF + (pl.n() - 1) * T + 1);
                ctx.op(n + " platform and stair tiles laid; entrance on the south platform");
            });

        ctx.stage("validation", "Constraint checks", "validation",
            "Walk the labyrinth from the entrance on the voxels; fragments that cannot be reached are kept as ornament. Find every apparent join of the isometric view.", () -> {
                Plan pl = plan[0];
                // fragments that the walk cannot reach become ornament, honestly labelled
                BitSet reach = Walk.reachable(g, ctx.entranceIndices());
                fragmentComp[0] = ctx.comps.add("Unreachable fragments (ornament)", "path", Material.PLASTER, false, false);
                int frag = 0;
                for (int i = 0; i < g.size(); i++) {
                    byte s = g.getIndex(i);
                    if (Cell.isStandable(s) && g.compIndex(i) == main[0] && !reach.get(i)) {
                        g.set(g.xOf(i), g.yOf(i), g.zOf(i), s, fragmentComp[0]);
                        frag++;
                    }
                }
                standardValidation(ctx, true, 2, 3, 3);
                ctx.report.add("fragments", "Walkable labyrinth", "info", frag == 0 ? Status.PASS : Status.WARN,
                    frag == 0 ? "every platform and stair is reachable from the entrance"
                        : frag + " walkable cells sit on fragments no stair reaches; they are drawn as ornament and marked as not required", frag);
                // apparent joins in the isometric view: tiles that project onto neighbouring screen positions
                apparent[0] = apparentJoins(pl, joinExamples);
                ctx.report.add("illusion", "Apparent joins (not walkable)", "info", apparent[0] > 0 ? Status.WARN : Status.PASS,
                    apparent[0] == 0 ? "no platforms line up along the isometric view axis in this design"
                        : apparent[0] + " places where two platforms appear to continue into each other in the isometric view but are levels apart; none is walkable"
                            + (joinExamples.isEmpty() ? "" : " (e.g. " + String.join("; ", joinExamples) + ")"), apparent[0]);
                ctx.report.add("plan", "Plan solved by 3D WFC", pl.failure() == null ? "hard" : "info",
                    pl.failure() == null ? Status.PASS : Status.WARN,
                    pl.failure() == null ? pl.attempts() + " WFC run(s) for " + pl.candidates() + " candidate solutions" + (pl.anchorsDropped() ? "; anchor platforms had to be relaxed" : "; every anchor platform placed")
                        : pl.failure(), pl.attempts());
            });

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Potted trees and lanterns on platforms, lamps on pillars.", () -> {
                ctx.runProgram("detailing", defaultProgram("detailing", p));
                ctx.op("isometric apparent joins found: " + apparent[0]);
            });
    }

    /**
     * Counts pairs of platform tiles that the isometric view (looking down (1,1,1)) shows as
     * edge-neighbours although they are not adjacent in 3D: tiles A and B = A + u + k(1,1,1) with
     * u a unit step in x or z and k != 0.
     */
    static int apparentJoins(Plan pl, List<String> examples) {
        int n = pl.n(), L = pl.levels(), count = 0;
        int[][] steps = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int y = 0; y < L; y++)
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++) {
                    if (!isPlatform(pl.at(x, y, z))) continue;
                    for (int[] u : steps)
                        for (int k = 1; k < L; k++) {
                            int bx = x + u[0] + k, by = y + k, bz = z + u[1] + k;
                            if (bx < 0 || bz < 0 || bx >= n || bz >= n || by >= L) continue;
                            if (!isPlatform(pl.at(bx, by, bz))) continue;
                            count++;
                            if (examples.size() < 2)
                                examples.add("tile (" + x + "," + y + "," + z + ") seems to meet tile (" + bx + "," + by + "," + bz + "), " + k + " level" + (k > 1 ? "s" : "") + " higher");
                        }
                }
        return count;
    }

    // ---- refinement -------------------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        for (int i = 1; i < probe.volumes().size(); i++) {
            rp.movable.add(i);
            rp.resizable.add(i);
        }
        int n = p.i("size"), L = p.i("levels");
        rp.minStoreys = 1;
        rp.maxStoreys = L;
        rp.objective("illusion", "Optical joins", "Anchor platforms that line up along the isometric view axis with another anchor.",
            m -> {
                int pairs = 0;
                List<Volume> vs = m.volumes();
                for (int i = 0; i < vs.size(); i++)
                    for (int j = 0; j < vs.size(); j++) {
                        if (i == j) continue;
                        int[] a = anchorTile(vs.get(i), n, L), b = anchorTile(vs.get(j), n, L);
                        int k = b[1] - a[1];
                        if (k <= 0) continue;
                        int ux = b[0] - a[0] - k, uz = b[2] - a[2] - k;
                        if (Math.abs(ux) + Math.abs(uz) == 1) pairs++;
                    }
                return 2.0 / (1 + pairs);
            });
        rp.objective("silhouette", "Layered heights", "Anchors spread over the levels rather than bunching on one.",
            m -> Objectives.variety(m, Objectives.role("anchor"), 0.5));
        rp.objective("balance", "Balanced composition", "Anchors balanced around the lattice centre.",
            m -> Objectives.balance(m, Objectives.role("anchor")));
        rp.hard("anchors inside the lattice", m -> {
            for (Volume v : m.volumes()) {
                int x = Math.floorDiv(v.cx() - OFF, T), z = Math.floorDiv(v.cz() - OFF, T);
                if (x < 0 || z < 0 || x >= n || z >= n || v.storeys() < 1 || v.storeys() > L) return v.name() + " leaves the lattice";
            }
            return null;
        });
        rp.hard("distinct anchors", m -> {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Volume v : m.volumes()) {
                int[] a = anchorTile(v, n, L);
                if (!seen.add(key(a[0], a[1], a[2]))) return v.name() + " shares a tile with another anchor";
            }
            return null;
        });
        return rp;
    }
}
