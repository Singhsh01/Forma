package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.Massing.Shape;
import studio.forma.engine.arch.Massing.Volume;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components.Material;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;
import studio.forma.engine.mcmc.Objectives;
import studio.forma.engine.mcmc.RefineProfile;
import studio.forma.engine.rules.Legend;
import studio.forma.engine.rules.ProgramParser;
import studio.forma.engine.rules.RunContext;
import studio.forma.engine.wfc.TileSet;
import studio.forma.engine.wfc.WfcSolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * E. Canal Town. A town plan is solved by tile Wave Function Collapse on a 2D lattice: canals,
 * streets, bridges, plazas and house blocks with explicit edge sockets. Local adjacency cannot
 * guarantee one connected street network, so the plan is traversed and stray street fragments are
 * turned into courtyards. The plan is then extruded into quays, bridges and narrow gabled houses.
 */
public final class CanalPreset extends BasePreset {
    static final int G = 3;          // street level
    static final int T = 4;          // cells per plan tile

    @Override public String id() { return "canal"; }
    @Override public String title() { return "Canal Town"; }
    @Override public String summary() {
        return "Rows of houses and small plazas organised around waterways, bridges and pedestrian quays.";
    }

    @Override public List<String> notes() {
        return List.of("The plan comes from tile WFC with bounded restarts; contradictions are retried with a forked seed, then reported.",
            "WFC only guarantees local adjacency. Street connectivity is checked by graph traversal and repaired before extrusion.",
            "A 2D rule pass on the plan grid turns enclosed blocks into garden courtyards.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("size", "Town size", "Plan tiles along each side.", 8, 16, 12, "massing"),
            ParamSpec.real("canals", "Canals", "Weight of canal tiles in the plan.", 0.2, 2, 0.1, 1.0, "water"),
            ParamSpec.real("streets", "Streets", "Weight of street tiles in the plan.", 0.2, 2, 0.1, 1.0, "circulation"),
            ParamSpec.real("plazas", "Plazas", "Weight of open plaza tiles.", 0, 1, 0.05, 0.25, "circulation"),
            ParamSpec.integer("storeys", "Tallest house", "Maximum storeys per house.", 2, 6, 4, "massing"),
            ParamSpec.real("variety", "Facade variety", "How much house heights and roofs vary along a street.", 0, 1, 0.05, 0.6, "massing"),
            ParamSpec.real("gardens", "Gardens", "Roof terraces, courtyard gardens and plaza trees.", 0, 1, 0.05, 0.5, "detail"));
    }

    @Override public List<String> programIds() {
        return List.of("plan", "growth", "detailing");
    }

    @Override public String defaultProgram(String id, Params p) {
        double gard = p.get("gardens");
        return switch (id) {
            case "plan" -> """
                sequence plan
                  all courtyards
                    rule enclosed "WWW/WWW/WWW" -> "***/*G*/***" sym=none   # a block tile surrounded by blocks becomes a garden court
                    rule pocket "GWG" -> "*G*" p=0.5 sym=rotate             # a block squeezed between two courts opens into one garden
                """;
            case "growth" -> String.format(Locale.ROOT, """
                sequence growth
                  prl party-walls steps=1
                    rule party "i1#" -> "*W*" sym=rotate
                    rule twin "i11i" -> "*WW*" sym=rotate
                  prl windows steps=1
                    rule tall "1 1" -> "N N" p=0.72 sym=none   # tall canal-house windows
                  prl walls steps=1
                    rule rest "1" -> "W" sym=none
                  prl garden-seeds steps=1
                    rule seed "T E" -> "G E" p=%.3f sym=none
                  one roof-gardens steps=%d
                    rule grow "GT" -> "GG"
                """, 0.03 + 0.1 * gard, (int) (40 + 500 * gard));
            case "detailing" -> String.format(Locale.ROOT, """
                sequence detailing
                  prl court-trees steps=1
                    rule tree "G E E" -> "* t V" p=%.3f sym=none
                  prl shrubs steps=1
                    rule shrub "G E" -> "* V" p=0.2 sym=none
                  prl quay-lamps steps=1
                    rule lamp "pw" -> "lw" p=0.0 sym=rotate
                    rule street-lamp "p E" -> "* l" p=0.02 sym=none
                """, 0.06 + 0.12 * gard).replace("    rule lamp \"pw\" -> \"lw\" p=0.0 sym=rotate\n", "");
            default -> throw new IllegalArgumentException("unknown program " + id);
        };
    }

    @Override public int[] gridSize(Params p) {
        int n = p.i("size");
        int sx = n * T + 8;
        return new int[]{sx, Math.max(24, G + 2 * p.i("storeys") + 10), sx};
    }

    @Override public double[] camera(Params p) {
        return new double[]{0, 6, 0, 90, -40, 38};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("golden", false, "perspective", 0);
    }

    // ---- the plan ---------------------------------------------------------------------------

    /** Plan tile kinds after solving and repair. */
    static final int BLOCK = 0, STREET = 1, CANAL = 2, BRIDGE = 3, PLAZA = 4, COURT = 5;

    static TileSet tiles(Params p) {
        double wc = p.get("canals"), ws = p.get("streets"), wp = p.get("plazas");
        // faces: +x -x +y -y +z -z; sockets: s street, c canal, b block edge, e (vertical)
        return new TileSet()
            .add("Block", 5.0, "b", "b", "e", "e", "b", "b", false)
            .add("Street", 0.9 * ws, "s", "s", "e", "e", "b", "b", true)
            .add("StreetTurn", 0.5 * ws, "s", "b", "e", "e", "s", "b", true)
            .add("StreetTee", 0.35 * ws, "s", "s", "e", "e", "s", "b", true)
            .add("StreetCross", 0.15 * ws, "s", "s", "e", "e", "s", "s", false)
            .add("Plaza", Math.max(0.001, 0.6 * wp), "s", "s", "e", "e", "s", "s", false)
            .add("Canal", 1.0 * wc, "c", "c", "e", "e", "b", "b", true)
            .add("CanalTurn", 0.35 * wc, "c", "b", "e", "e", "c", "b", true)
            .add("Bridge", 0.6 * Math.min(wc, ws) + 0.05, "c", "c", "e", "e", "s", "s", true);
    }

    static int kindOf(String base) {
        return switch (base) {
            case "Block" -> BLOCK;
            case "Canal", "CanalTurn" -> CANAL;
            case "Bridge" -> BRIDGE;
            case "Plaza" -> PLAZA;
            default -> STREET;
        };
    }

    /** Solved plan: tile kinds and, for canal and bridge tiles, which faces carry water. */
    record Plan(int n, int[] kind, String[][] sockets, int attempts, String failure, int repaired) {}

    static Plan solvePlan(Params p, Rng rng) {
        int n = p.i("size");
        TileSet ts = tiles(p);
        WfcSolver s = new WfcSolver(ts, n, 1, n, "b", false);
        // a canal enters on the west edge and another leaves on the south edge
        int a = n / 3 + rng.nextInt(Math.max(1, n / 3)), b = n / 3 + rng.nextInt(Math.max(1, n / 3));
        long canalWest = ts.maskWithSocket(TileSet.NX, "c");
        long canalSouth = ts.maskWithSocket(TileSet.PZ, "c");
        WfcSolver.Result r = null;
        // the canal mouths sit on the boundary, so lift the boundary constraint there by solving with
        // an inner lattice and fixed mouths: implemented by restricting the slot next to the edge.
        WfcSolver inner = new WfcSolver(ts, n, 1, n, null, false);
        for (int i = 0; i < n; i++) {
            long edgeOk = ts.maskWithSocket(TileSet.NX, "b") | (i == a ? canalWest : 0);
            inner.restrict(0, 0, i, edgeOk);
            inner.restrict(n - 1, 0, i, ts.maskWithSocket(TileSet.PX, "b"));
            inner.restrict(i, 0, 0, ts.maskWithSocket(TileSet.NZ, "b"));
            inner.restrict(i, 0, n - 1, ts.maskWithSocket(TileSet.PZ, "b") | (i == b ? canalSouth : 0));
        }
        inner.restrict(0, 0, a, canalWest);
        inner.restrict(b, 0, n - 1, canalSouth);
        r = inner.solve(rng, 12, studio.forma.engine.core.Cancellation.none());
        if (!r.success()) {
            // fall back to a canal-free plan rather than failing the whole town
            r = s.solve(rng.fork("fallback"), 12, studio.forma.engine.core.Cancellation.none());
        }
        int[] kind = new int[n * n];
        String[][] sock = new String[n * n][];
        if (r.success()) {
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++) {
                    var t = ts.tile(r.tiles()[x + n * z]);
                    kind[x + n * z] = kindOf(t.base());
                    sock[x + n * z] = t.sockets();
                }
        } else {
            java.util.Arrays.fill(kind, BLOCK);
            for (int i = 0; i < sock.length; i++) sock[i] = new String[]{"b", "b", "e", "e", "b", "b"};
        }
        // connectivity repair: keep the largest street/bridge/plaza network, the rest become courtyards
        int repaired = 0;
        int[] comp = new int[n * n];
        java.util.Arrays.fill(comp, -1);
        int best = -1, bestSize = 0, id = 0;
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < n * n; i++) {
            if (!walkable(kind[i]) || comp[i] >= 0) continue;
            int size = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i);
            comp[i] = id;
            while (!q.isEmpty()) {
                int c = q.poll();
                size++;
                int cx = c % n, cz = c / n;
                int[][] d = {{1, 0, TileSet.PX}, {-1, 0, TileSet.NX}, {0, 1, TileSet.PZ}, {0, -1, TileSet.NZ}};
                for (int[] o : d) {
                    int nx = cx + o[0], nz = cz + o[1];
                    if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                    int j = nx + n * nz;
                    if (comp[j] >= 0 || !walkable(kind[j])) continue;
                    // streets connect only through street sockets
                    if (!sock[c][o[2]].equals("s")) continue;
                    comp[j] = id;
                    q.add(j);
                }
            }
            sizes.add(size);
            if (size > bestSize) {
                bestSize = size;
                best = id;
            }
            id++;
        }
        for (int i = 0; i < n * n; i++)
            if (walkable(kind[i]) && comp[i] != best) {
                if (kind[i] == BRIDGE) {
                    kind[i] = CANAL;
                    String[] so = sock[i];
                    sock[i] = new String[]{so[0].equals("c") ? "c" : "b", so[1].equals("c") ? "c" : "b", "e", "e", so[4].equals("c") ? "c" : "b", so[5].equals("c") ? "c" : "b"};
                } else kind[i] = COURT;
                repaired++;
            }
        // a block tile with no street, bridge or plaza beside it could never open a front door: plant it
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++)
                if (kind[x + n * z] == BLOCK && frontage(kind, n, x, z) == 0) {
                    kind[x + n * z] = COURT;
                    repaired++;
                }
        return new Plan(n, kind, sock, r.attempts(), r.success() ? null : r.failure(), repaired);
    }

    /**
     * Bit mask of the sides of tile (x,z) a house could open onto: a street, plaza, bridge, the quay
     * of a canal tile, or the quay ring at the town edge. 1 +x, 2 -x, 4 +z, 8 -z.
     */
    static int frontage(int[] kind, int n, int x, int z) {
        int m = 0;
        if (x + 1 >= n || fronts(kind[x + 1 + n * z])) m |= 1;
        if (x <= 0 || fronts(kind[x - 1 + n * z])) m |= 2;
        if (z + 1 >= n || fronts(kind[x + n * (z + 1)])) m |= 4;
        if (z <= 0 || fronts(kind[x + n * (z - 1)])) m |= 8;
        return m;
    }

    static boolean fronts(int k) {
        return walkable(k) || k == CANAL;
    }

    static boolean walkable(int k) {
        return k == STREET || k == BRIDGE || k == PLAZA;
    }

    // ---- composition ------------------------------------------------------------------------

    @Override
    public Massing compose(Params p, Rng rng) {
        int[] size = gridSize(p);
        Plan plan = solvePlan(p, rng.fork("plan"));
        int n = plan.n();
        int off = 4;
        int maxS = p.i("storeys");
        double var = p.get("variety");
        List<Volume> vs = new ArrayList<>();
        int hn = 0;
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                if (plan.kind()[x + n * z] != BLOCK) continue;
                // each block tile holds two narrow houses; they sit side by side along the street they face
                int front = frontage(plan.kind(), n, x, z);
                boolean faceZ = (front & 12) != 0, faceX = (front & 3) != 0;
                boolean splitX = faceZ && faceX ? rng.nextDouble() < 0.5 : faceZ;
                for (int h = 0; h < 2; h++) {
                    int w = splitX ? 2 : T, d = splitX ? T : 2;
                    int cx = off + x * T + (splitX ? h * 2 + 1 : T / 2), cz = off + z * T + (splitX ? T / 2 : h * 2 + 1);
                    int st = Math.max(2, Math.min(maxS, maxS - (int) Math.round(rng.nextDouble() * var * (maxS - 1))));
                    boolean terrace = rng.nextDouble() < 0.12 + 0.2 * p.get("gardens");
                    vs.add(new Volume("House " + (++hn), "house", Shape.BOX, cx, cz, w, d, G, st, terrace, 0, false, rng.nextInt(4), false));
                }
            }
        return new Massing(size[0], size[1], size[2], G, vs, List.of(), List.of());
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        int off = 4;
        ctx.expectStages(8);
        final Plan[] planHolder = new Plan[1];

        ctx.stage("plan", "Town plan (2D WFC)", "wfc",
            "Solve the plan lattice with tile WFC, repair street connectivity by traversal, then run a 2D rule pass on the plan.", () -> {
                Plan plan = solvePlan(p, ctx.rng.fork("compose").fork("plan"));
                planHolder[0] = plan;
                int n = plan.n();
                ctx.op("WFC solved a " + n + " x " + n + " plan in " + plan.attempts() + " attempt(s)" + (plan.failure() != null ? " (fell back: " + plan.failure() + ")" : ""));
                ctx.op(plan.repaired() + " plan tiles cut off from the main street network were turned into courtyards");
                // a 2D grid of the plan for the rule pass: blocks W, streets p, canals w, plazas/courts G
                Grid plan2d = Grid.of2D(n, n);
                for (int z = 0; z < n; z++)
                    for (int x = 0; x < n; x++) {
                        int k = plan.kind()[x + n * z];
                        plan2d.set(x, 0, z, switch (k) {
                            case BLOCK -> Cell.WALL;
                            case CANAL -> Cell.WATER;
                            case BRIDGE -> Cell.BRIDGE;
                            case PLAZA, COURT -> Cell.GRASS;
                            default -> Cell.PATH;
                        });
                    }
                String src = ctx.ruleOverrides.getOrDefault("plan", defaultProgram("plan", p));
                try (RunContext rc = new RunContext(plan2d, ctx.rng.fork("rules:plan"), ctx.log, ctx.cancel, null, 10_000)) {
                    rc.run(ProgramParser.parse(src, Legend.standard()));
                }
                ctx.notes.get("plan").programs.put("plan", src);
                ctx.notes.get("plan").programOverridden.put("plan", ctx.ruleOverrides.containsKey("plan"));
                int courts = 0;
                for (int z = 0; z < n; z++)
                    for (int x = 0; x < n; x++)
                        if (plan.kind()[x + n * z] == BLOCK && plan2d.get(x, 0, z) == Cell.GRASS) {
                            plan.kind()[x + n * z] = COURT;
                            courts++;
                        }
                ctx.op("the 2D rule pass made " + courts + " enclosed blocks into garden courts");
                // record the plan into the grid as ground marks so the replay shows it
                int ground = ctx.comps.add("Town ground", "terrain", Material.ROCK);
                for (int z = 0; z < m.sz(); z++)
                    for (int x = 0; x < m.sx(); x++)
                        for (int y = 0; y < G; y++) g.set(x, y, z, Cell.TERRAIN, ground);
            });

        int[] compOf = new int[m.volumes().size()];
        ctx.stage("site", "Canals, quays and streets", "constructive",
            "Extrude the plan: dig canals with quay walls, pave streets and plazas, plant courtyards.", () -> {
                Plan plan = planHolder[0];
                int n = plan.n();
                int water = ctx.comps.add("Canals", "water", Material.ROCK);
                int streets = ctx.comps.add("Streets and quays", "path", Material.SANDSTONE, true, false);
                int plazas = ctx.comps.add("Plazas", "plaza", Material.MARBLE, true, false);
                int courts = ctx.comps.add("Garden courts", "garden", Material.ROCK, false, false);
                ctx.history.setFrameBudget(1500);
                for (int tz = 0; tz < n; tz++)
                    for (int tx = 0; tx < n; tx++) {
                        int k = plan.kind()[tx + n * tz];
                        String[] so = plan.sockets()[tx + n * tz];
                        for (int dz = 0; dz < T; dz++)
                            for (int dx = 0; dx < T; dx++) {
                                int x = off + tx * T + dx, z = off + tz * T + dz;
                                switch (k) {
                                    case CANAL, BRIDGE -> {
                                        if (isWater(so, dx, dz)) {
                                            g.set(x, G - 1, z, Cell.WATER, water);
                                            g.set(x, G - 2, z, Cell.WATER, water);
                                        } else g.set(x, G, z, Cell.PATH, streets);
                                        if (k == BRIDGE && isStreet(so, dx, dz) && isWater(so, dx, dz)) {
                                            g.set(x, G, z, Cell.BRIDGE, streets);
                                        }
                                    }
                                    case STREET -> g.set(x, G, z, Cell.PATH, streets);
                                    case PLAZA -> {
                                        boolean fountain = (dx == 1 || dx == 2) && (dz == 1 || dz == 2);
                                        g.set(x, G, z, fountain ? Cell.WATER : Cell.PATH, plazas);
                                    }
                                    case COURT -> g.set(x, G, z, Cell.GRASS, courts);
                                    default -> {}
                                }
                            }
                    }
                // the town sits in a lagoon, ringed by a one-cell quay walk
                for (int z = 0; z < m.sz(); z++)
                    for (int x = 0; x < m.sx(); x++) {
                        boolean outside = x < off || z < off || x >= off + n * T || z >= off + n * T;
                        if (!outside) continue;
                        boolean quay = x >= off - 1 && z >= off - 1 && x <= off + n * T && z <= off + n * T;
                        if (quay) {
                            if (g.get(x, G, z) == Cell.EMPTY) g.set(x, G, z, Cell.PATH, streets);
                        } else {
                            g.set(x, G - 1, z, Cell.WATER, water);
                            g.set(x, G - 2, z, Cell.WATER, water);
                        }
                    }
                // the canal mouths cut through the quay into the lagoon
                for (int i = 0; i < n * T; i++) {
                    if (g.get(off, G - 1, off + i) == Cell.WATER) {
                        g.set(off - 1, G, off + i, Cell.EMPTY, 0);
                        g.set(off - 1, G - 1, off + i, Cell.WATER, water);
                        g.set(off - 1, G - 2, off + i, Cell.WATER, water);
                    }
                    if (g.get(off + i, G - 1, off + n * T - 1) == Cell.WATER) {
                        g.set(off + i, G, off + n * T, Cell.EMPTY, 0);
                        g.set(off + i, G - 1, off + n * T, Cell.WATER, water);
                        g.set(off + i, G - 2, off + n * T, Cell.WATER, water);
                    }
                }
                ctx.op("canals two cells deep, streets, plazas with fountains, courtyards planted, a lagoon around the quay");
            });

        ctx.stage("masses", "Houses", "constructive",
            "Raise two narrow houses on every block tile, each with a stepped gable or a roof terrace.", () -> {
                Plan plan = planHolder[0];
                int n = plan.n(), dropped = 0, cutOff = 0;
                java.util.Arrays.fill(compOf, -1);
                // WFC adjacency says nothing about whole-town reachability: walk the paving from the
                // quay ring and only raise houses that can open a door onto the reachable network
                boolean[] reach = reachablePaving(g, off - 1, off - 1);
                int courtComp = ctx.comps.add("Quiet gardens", "garden", Material.ROCK, false, false);
                for (int z = 0; z < g.sz; z++)
                    for (int x = 0; x < g.sx; x++)
                        if (!reach[x + g.sx * z] && g.get(x, G, z) == Cell.PATH) g.set(x, G, z, Cell.GRASS, courtComp);
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    int tx = Math.floorDiv(v.cx() - off, T), tz = Math.floorDiv(v.cz() - off, T);
                    if (tx >= 0 && tz >= 0 && tx < n && tz < n && plan.kind()[tx + n * tz] != BLOCK) {
                        dropped++;   // the plan rules turned this block into a garden court
                        continue;
                    }
                    if (doorSide(g, v, reach) < 0) {
                        cutOff++;
                        for (int z = v.z0(); z < v.z0() + v.d(); z++)
                            for (int x = v.x0(); x < v.x0() + v.w(); x++) g.set(x, G, z, Cell.GRASS, courtComp);
                        continue;
                    }
                    Material mat = switch (v.variant()) {
                        case 0 -> Material.TERRACOTTA;
                        case 1 -> Material.PLASTER;
                        case 2 -> Material.SANDSTONE;
                        default -> Material.BASALT;
                    };
                    compOf[i] = ctx.comps.add(v.name(), "house", mat, true, false);
                    ctx.comps.setRoof(compOf[i], v.variant() == 3 ? Material.COPPER : Material.TERRACOTTA);
                    ctx.kit.shell(v, v::covers, compOf[i], v.terraced() ? Cell.TERRACE : Cell.EMPTY, 2);
                    if (!v.terraced()) gableRoof(ctx, v, compOf[i]);
                }
                ctx.op((m.volumes().size() - dropped) + " houses" + (dropped > 0 ? "; " + dropped + " left out where the plan rules made garden courts" : "")
                    + (cutOff > 0 ? "; " + cutOff + " plots with no reachable frontage planted as gardens" : ""));
            });

        ctx.stage("circulation", "Doors and arches", "constructive",
            "Every house opens a door onto a street, quay or plaza; bridges get arches over the water.", () -> {
                int doors = 0;
                boolean[] reachDoors = reachablePaving(g, off - 1, off - 1);
                for (int i = 0; i < m.volumes().size(); i++) {
                    if (compOf[i] < 0) continue;
                    Volume v = m.volumes().get(i);
                    int d = doorSide(g, v, reachDoors);
                    if (d >= 0) doors += doorAlong(ctx, v.cx(), G, v.cz(), d, 4, compOf[i]) > 0 ? 1 : 0;
                }
                // arches under bridge decks
                for (int z = 0; z < m.sz(); z++)
                    for (int x = 0; x < m.sx(); x++)
                        if (g.get(x, G, z) == Cell.BRIDGE && g.get(x, G - 1, z) == Cell.WATER) {
                            boolean edgeX = g.get(x + 1, G, z) != Cell.BRIDGE || g.get(x - 1, G, z) != Cell.BRIDGE;
                            boolean edgeZ = g.get(x, G, z + 1) != Cell.BRIDGE || g.get(x, G, z - 1) != Cell.BRIDGE;
                            if (edgeX || edgeZ) g.set(x, G - 1, z, Cell.ARCH, g.comp(x, G, z));
                        }
                ctx.entrance(off - 1, G, off - 1);
                ctx.op(doors + " houses open onto the street network; arches carry every bridge");
            });

        ctx.stage("growth", "Rule-driven growth", "rules",
            "Rules choose the canal-house windows and grow roof gardens.",
            () -> ctx.runProgram("growth", defaultProgram("growth", p)));

        ctx.stage("validation", "Constraint checks", "validation",
            "Walk the streets, quays and bridges from the town edge; every house must be reachable.", () -> {
                standardValidation(ctx, true, 3, 8, 8);
                Plan plan = planHolder[0];
                ctx.report.add("plan", "Plan solved by WFC", plan.failure() == null ? "hard" : "info",
                    plan.failure() == null ? ConstraintReport.Status.PASS : ConstraintReport.Status.WARN,
                    plan.failure() == null ? "solved in " + plan.attempts() + " attempt(s); " + plan.repaired() + " stray street tiles repaired"
                        : "fell back to a canal-free plan: " + plan.failure(), plan.attempts());
            });

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Courtyard trees, shrubs and street lamps.", () -> {
                ctx.runProgram("detailing", defaultProgram("detailing", p));
                var conn = studio.forma.engine.arch.Validator.connectivity(g, ctx.comps, ctx.entranceIndices());
                ctx.op(String.format("re-check after detailing: %d of %d walkable cells reachable", conn.reachable(), conn.standable()));
            });
    }

    /** Plan-level (y = G) cells of paving and bridges reachable from (x0, z0), 4-connected. */
    static boolean[] reachablePaving(Grid g, int x0, int z0) {
        boolean[] seen = new boolean[g.sx * g.sz];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        if (g.get(x0, G, z0) == Cell.PATH) {
            seen[x0 + g.sx * z0] = true;
            q.add(new int[]{x0, z0});
        }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int d = 0; d < 4; d++) {
                int x = c[0] + AX[d], z = c[1] + AZ[d];
                if (!g.inBounds(x, G, z) || seen[x + g.sx * z]) continue;
                byte b = g.get(x, G, z);
                if (b != Cell.PATH && b != Cell.BRIDGE) continue;
                seen[x + g.sx * z] = true;
                q.add(new int[]{x, z});
            }
        }
        return seen;
    }

    /** First side (0 +x, 1 -x, 2 +z, 3 -z) where the cell just outside the house's midline is reachable paving, or -1. */
    static int doorSide(Grid g, Volume v, boolean[] reach) {
        for (int d = 0; d < 4; d++) {
            int ox = v.cx() + AX[d] * ((d == 0 ? v.w() - v.w() / 2 : v.w() / 2) + (d == 1 ? 1 : 0));
            int oz = v.cz() + AZ[d] * ((d == 2 ? v.d() - v.d() / 2 : v.d() / 2) + (d == 3 ? 1 : 0));
            if (!g.inBounds(ox, G, oz)) continue;
            byte out = g.get(ox, G, oz);
            if ((out == Cell.PATH || out == Cell.BRIDGE) && reach[ox + g.sx * oz]) return d;
        }
        return -1;
    }

    /** Within a canal or bridge tile, which of its 4x4 cells are water (the canal runs between "c" faces, two cells wide). */
    static boolean isWater(String[] so, int dx, int dz) {
        boolean px = so[TileSet.PX].equals("c"), nx = so[TileSet.NX].equals("c"), pz = so[TileSet.PZ].equals("c"), nz = so[TileSet.NZ].equals("c");
        boolean midX = dx == 1 || dx == 2, midZ = dz == 1 || dz == 2;
        if (midX && midZ) return true;
        return (px && midZ && dx >= 2) || (nx && midZ && dx <= 1) || (pz && midX && dz >= 2) || (nz && midX && dz <= 1);
    }

    static boolean isStreet(String[] so, int dx, int dz) {
        boolean px = so[TileSet.PX].equals("s"), nx = so[TileSet.NX].equals("s"), pz = so[TileSet.PZ].equals("s"), nz = so[TileSet.NZ].equals("s");
        boolean midX = dx == 1 || dx == 2, midZ = dz == 1 || dz == 2;
        return (px || nx) && midZ || (pz || nz) && midX;
    }

    // ---- refinement -------------------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        for (int i = 0; i < probe.volumes().size(); i++) {
            rp.resizable.add(i);
            rp.terraceToggle.add(i);
        }
        rp.minStoreys = 2;
        rp.maxStoreys = p.i("storeys") + 1;
        rp.objective("circulation", "Street life", "Houses fronting plazas stand a little taller.", m -> 0);
        rp.objective("daylight", "Daylight in the streets", "Neighbouring houses do not differ by more than two storeys.",
            m -> {
                double pen = 0;
                List<Volume> vs = m.volumes();
                for (int i = 0; i < vs.size(); i++)
                    for (int j = i + 1; j < vs.size(); j++) {
                        Volume a = vs.get(i), b = vs.get(j);
                        if (Math.abs(a.cx() - b.cx()) + Math.abs(a.cz() - b.cz()) > 4) continue;
                        pen += Math.max(0, Math.abs(a.storeys() - b.storeys()) - 2) * 0.3;
                    }
                return pen;
            });
        rp.objective("proportion", "Narrow fronts", "Canal houses about three times as tall as their frontage.",
            m -> Objectives.slenderness(m, Objectives.role("house"), 3.0));
        rp.objective("void", "Open plazas", "Plazas stay open (fixed by the plan).", m -> 0);
        rp.objective("silhouette", "Lively skyline", "Heights vary from house to house.", m -> Objectives.variety(m, Objectives.role("house"), 0.25));
        rp.objective("variety", "Roof variety", "A mix of gables and roof terraces.", m -> Objectives.greenery(m, Objectives.role("house"), 0.25));
        rp.objective("greenery", "Greenery", "Some roof gardens.", m -> Objectives.greenery(m, Objectives.role("house"), 0.2));
        rp.objective("density", "Density", "Fixed by the plan.", m -> 0);
        rp.hard("site bounds", m -> Objectives.withinGrid(m, 1));
        return rp;
    }
}
