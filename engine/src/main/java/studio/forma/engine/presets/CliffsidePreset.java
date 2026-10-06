package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.Massing.Link;
import studio.forma.engine.arch.Massing.Shape;
import studio.forma.engine.arch.Massing.Volume;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components.Material;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;
import studio.forma.engine.mcmc.Objectives;
import studio.forma.engine.mcmc.RefineProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B. Cliffside City. A cliff rises from the sea in stepped benches held by retaining walls.
 * Whitewashed houses line each bench, switchback stairs climb against the walls, a ravine splits
 * the town and lookout bridges cross it.
 */
public final class CliffsidePreset extends BasePreset {
    static final int SEA = 2;
    static final int QUAY = 4;

    @Override public String id() { return "cliffside"; }
    @Override public String title() { return "Cliffside City"; }
    @Override public String summary() {
        return "Houses climb a stepped cliff, tied together by terraces, stairs, retaining walls and lookout bridges.";
    }

    @Override public List<String> notes() {
        return List.of("Retaining walls, stairs and bridges are placed by code; window rhythm, gardens and planters come from rules.",
            "The ravine is a protected void: no house may be placed in it, and bridges must leave it open below.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("benches", "Terraces", "Number of stepped benches up the cliff.", 3, 7, 5, "terrain"),
            ParamSpec.integer("rise", "Cliff steepness", "Height of each bench in cells (two cells per storey).", 4, 8, 6, "terrain"),
            ParamSpec.real("density", "Density", "How tightly houses line each terrace.", 0.2, 1, 0.05, 0.7, "massing"),
            ParamSpec.integer("storeys", "Tallest house", "Maximum storeys per house.", 1, 4, 3, "massing"),
            ParamSpec.toggle("ravine", "Ravine", "Split the cliff with a gorge crossed by bridges.", true, "terrain"),
            ParamSpec.real("bridges", "Bridge frequency", "Share of terraces whose ravine crossing is built.", 0, 1, 0.05, 0.7, "circulation"),
            ParamSpec.real("gardens", "Gardens", "Roof gardens, planters and trees.", 0, 1, 0.05, 0.5, "detail"),
            ParamSpec.real("variation", "Variation", "Variety of house sizes and roof types.", 0, 1, 0.05, 0.5, "massing"));
    }

    @Override public List<String> programIds() {
        return List.of("growth", "detailing");
    }

    @Override public String defaultProgram(String id, Params p) {
        double gard = p.get("gardens");
        return switch (id) {
            case "growth" -> String.format(Locale.ROOT, """
                sequence growth
                  prl party-walls steps=1
                    rule party "i1#" -> "*W*" sym=rotate      # bays against a neighbour or the cliff close up
                    rule twin "i11i" -> "*WW*" sym=rotate
                  prl deep-windows steps=1
                    rule small "1 1" -> "W N" p=0.7 sym=none  # small, deep-set upper windows
                  prl shutters steps=1
                    rule leftover "1" -> "W" sym=none         # remaining bays stay solid whitewash
                  prl wall-planters steps=1
                    rule planter "2 E" -> "2 V" p=%.3f sym=none  # bougainvillea along retaining wall tops
                  prl walls-done steps=1
                    rule settle "2" -> "W" sym=none           # retaining walls become ordinary masonry
                  prl garden-seeds steps=1
                    rule seed "T E" -> "G E" p=%.3f sym=none
                  one roof-gardens steps=%d
                    rule grow "GT" -> "GG"
                """, 0.04 + 0.25 * gard, 0.02 + 0.08 * gard, (int) (40 + 600 * gard));
            case "detailing" -> String.format(Locale.ROOT, """
                sequence detailing
                  prl trees steps=1
                    rule tree "p E E" -> "G t V" p=%.4f sym=none   # olive trees break the paving
                  prl pots steps=1
                    rule pot "T E" -> "* V" p=%.3f sym=none       # potted plants on roof terraces
                  prl lamps steps=1
                    rule lamp "p E" -> "* l" p=0.007 sym=none     # street lamps along the terraces
                """, 0.002 + 0.008 * gard, 0.03 + 0.12 * gard);
            default -> throw new IllegalArgumentException("unknown program " + id);
        };
    }

    @Override public int[] gridSize(Params p) {
        int b = p.i("benches"), rise = p.i("rise");
        int sy = Math.max(40, QUAY + b * rise + 2 * p.i("storeys") + 12);
        return new int[]{76, sy, 68};
    }

    @Override public double[] camera(Params p) {
        return new double[]{0, 18, 0, 100, -28, 26};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("morning", false, "perspective", 0);
    }

    record Bench(int k, int zLo, int zHi, int y) {}

    static List<Bench> benches(Params p, int sz) {
        int b = p.i("benches"), rise = p.i("rise");
        int front = sz - 12;
        int depth = Math.max(7, (front - 4) / b);
        List<Bench> out = new ArrayList<>();
        int zHi = front;
        for (int k = 0; k < b; k++) {
            int zLo = Math.max(2, zHi - depth);
            out.add(new Bench(k, zLo, zHi, QUAY + k * rise));
            zHi = zLo;
        }
        return out;
    }

    static int[] ravine(Params p, int sx) {
        if (!p.b("ravine")) return null;
        return new int[]{sx / 2 - 3, sx / 2 + 3};
    }

    // ---- composition ------------------------------------------------------------------------

    @Override
    public Massing compose(Params p, Rng rng) {
        int[] size = gridSize(p);
        int sx = size[0];
        List<Bench> bs = benches(p, size[2]);
        int[] rav = ravine(p, sx);
        double dens = p.get("density"), var = p.get("variation");
        int maxS = p.i("storeys");
        List<Volume> vs = new ArrayList<>();
        int n = 0;
        for (Bench b : bs) {
            int depth = b.zHi() - b.zLo();
            int x = 3 + rng.nextInt(3);
            while (x < sx - 5) {
                int w = 4 + rng.nextInt(2 + (int) Math.round(3 * var));
                int d = Math.min(depth - 3, 4 + rng.nextInt(3 + (int) Math.round(2 * var)));
                int x1 = x + w;
                boolean inRavine = rav != null && x1 > rav[0] - 1 && x < rav[1] + 1;
                boolean stairZone = inStairZone(b.k(), x, x1, sx, rav, p.i("rise"), bs.size());
                if (x1 >= sx - 3 || inRavine || stairZone || rng.nextDouble() > dens) {
                    x += 2 + rng.nextInt(3);
                    continue;
                }
                int st = 1 + rng.nextInt(maxS);
                if (b.k() == bs.size() - 1) st = Math.min(maxS + 1, st + 1); // the top bench gets the taller houses
                boolean terraced = rng.nextDouble() < 0.35 + 0.3 * var;
                // houses stand at the back of the bench, leaving a promenade along the front edge
                int cz = b.zLo() + 1 + d / 2;
                vs.add(new Volume("House " + (++n), "house", Shape.BOX, x + w / 2, cz, w, d, b.y(), st, terraced, 0, true, rng.nextInt(3), false));
                x = x1 + 1 + rng.nextInt(2 + (int) Math.round(2 * (1 - dens)));
            }
        }
        // a lookout tower at the top of the cliff
        Bench top = bs.get(bs.size() - 1);
        int tx = rav != null ? rav[1] + 6 : sx - 10;
        boolean free = true;
        for (Volume v : vs) if (v.y0() == top.y() && Math.abs(v.cx() - tx) < v.w() / 2 + 3) free = false;
        if (inStairZone(top.k(), tx - 2, tx + 2, sx, rav, p.i("rise"), bs.size())) free = false;
        if (free) vs.add(new Volume("Lookout Tower", "tower", Shape.BOX, tx, top.zLo() + 3, 4, 4, top.y(), maxS + 2, true, 0, false, 0, false));
        List<Link> links = new ArrayList<>();
        double bf = p.get("bridges");
        if (rav != null) {
            for (Bench b : bs) {
                if (b.k() == 0) continue;
                links.add(new Link(-1, b.k(), b.y(), "ravine bridge", rng.nextDouble() < bf || b.k() == bs.size() - 1));
            }
        }
        List<Massing.Void> voids = new ArrayList<>();
        if (rav != null) voids.add(new Massing.Void("Ravine", sx / 2, size[2] / 2, 3, QUAY, size[1]));
        return new Massing(sx, size[1], size[2], QUAY, vs, links, voids);
    }

    /** Stairs climb against each retaining wall: left half rises east on even benches, west on odd ones. */
    static int[] stairX(int k, int sx, int[] rav) {
        int leftMid = rav != null ? rav[0] / 2 : sx / 4, rightMid = rav != null ? (rav[1] + sx) / 2 : 3 * sx / 4;
        return new int[]{leftMid + (k % 2 == 0 ? -6 : 2), rightMid + (k % 2 == 0 ? 2 : -6)};
    }

    /** Only the foot of each stair needs a gap in the house line (the stair itself runs behind the houses). */
    static boolean inStairZone(int k, int x0, int x1, int sx, int[] rav, int rise, int benchCount) {
        if (k >= benchCount - 1) return false;
        int[] xs = stairX(k, sx, rav);
        for (int half = 0; half < (rav != null ? 2 : 1); half++) {
            int dir = (k + half) % 2 == 0 ? 0 : 1;
            int foot = dir == 0 ? xs[half] - 1 : xs[half] + rise + 2;
            if (x1 > foot - 2 && x0 < foot + 3) return true;
        }
        return false;
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        List<Bench> bs = benches(p, m.sz());
        int[] rav = ravine(p, m.sx());
        int rise = p.i("rise");
        ctx.expectStages(7);

        ctx.stage("site", "Cliff, sea and retaining walls", "constructive",
            "Raise the stepped cliff from the sea, face each bench with a retaining wall and cut the ravine.", () -> {
                int rock = ctx.comps.add("Cliff", "terrain", Material.ROCK);
                int walls = ctx.comps.add("Retaining walls", "wall", Material.SANDSTONE);
                int sea = ctx.comps.add("Sea", "water", Material.ROCK);
                ctx.history.setFrameBudget(3500);
                Rng r = ctx.rng.fork("site");
                long seed = r.nextLong();
                for (int z = 0; z < m.sz(); z++)
                    for (int x = 0; x < m.sx(); x++) {
                        int top = SEA - 1;
                        Bench on = null;
                        for (Bench b : bs) if (z >= b.zLo() && z < b.zHi()) on = b;
                        if (on != null) top = on.y() - 1;
                        else if (z < bs.get(bs.size() - 1).zLo()) top = bs.get(bs.size() - 1).y() + rise / 2 + (int) (Rng.hash01(seed, x / 3, 0, z) * 2);
                        else if (z < bs.get(0).zHi() + 2) top = QUAY - 1;
                        if (rav != null && x >= rav[0] && x < rav[1] && on != null && on.k() > 0) top = QUAY - 1;
                        for (int y = 0; y <= top; y++) g.set(x, y, z, Cell.TERRAIN, rock);
                        for (int y = top + 1; y <= SEA; y++) g.set(x, y, z, Cell.WATER, sea);
                    }
                // retaining walls: the front face of every bench above the quay, and the ravine sides
                for (Bench b : bs) {
                    if (b.k() == 0) continue;
                    Bench below = bs.get(b.k() - 1);
                    int z = b.zHi() - 1;
                    for (int x = 0; x < m.sx(); x++) {
                        if (rav != null && x >= rav[0] && x < rav[1]) continue;
                        for (int y = below.y(); y < b.y(); y++) g.set(x, y, z, Cell.MARK_B, walls);
                    }
                    if (rav != null)
                        for (int z2 = b.zLo(); z2 < b.zHi(); z2++)
                            for (int y = QUAY; y < b.y(); y++) {
                                g.set(rav[0] - 1, y, z2, Cell.MARK_B, walls);
                                g.set(rav[1], y, z2, Cell.MARK_B, walls);
                            }
                }
                ctx.op(bs.size() + " benches of " + rise + " cells, retaining walls on every face" + (rav != null ? ", ravine cut to the quay" : ""));
            });

        int[] compOf = new int[m.volumes().size()];
        ctx.stage("masses", "Houses and towers", "constructive",
            "Rasterise each house as a storeyed shell with bays, then give it a gable roof or a roof terrace.", () -> {
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    Material mat = v.role().equals("tower") ? Material.SANDSTONE : switch (v.variant()) {
                        case 1 -> Material.TERRACOTTA;
                        case 2 -> Material.SANDSTONE;
                        default -> Material.PLASTER;
                    };
                    compOf[i] = ctx.comps.add(v.name(), v.role(), mat, true, false);
                    ctx.comps.setRoof(compOf[i], Material.TERRACOTTA);
                    ctx.kit.shell(v, v::covers, compOf[i], v.terraced() ? Cell.TERRACE : Cell.EMPTY, 2);
                    if (!v.terraced()) gableRoof(ctx, v, compOf[i]);
                }
                ctx.op(m.volumes().size() + " houses and towers on the benches");
            });

        ctx.stage("circulation", "Stairs, paths and bridges", "constructive",
            "Pave the bench tops, climb each retaining wall with a switchback stair, cross the ravine, open doors to the street.", () -> {
                int paths = ctx.comps.add("Streets and quay", "path", Material.SANDSTONE, true, false);
                for (Bench b : bs) pave(ctx, b.y(), paths, Cell.TERRAIN, Cell.MARK_B);
                pave(ctx, QUAY, paths);
                int stairs = ctx.comps.add("Switchback stairs", "stair", Material.SANDSTONE, true, false);
                int built = 0;
                for (Bench b : bs) {
                    if (b.k() == bs.size() - 1) break;
                    Bench up = bs.get(b.k() + 1);
                    int[] xs = stairX(b.k(), m.sx(), rav);
                    for (int half = 0; half < (rav != null ? 2 : 1); half++) {
                        int x0 = xs[half];
                        int dir = (b.k() + half) % 2 == 0 ? 0 : 1;
                        int sxStart = dir == 0 ? x0 : x0 + rise - 1 + 2;
                        int z = b.zLo();
                        if (climb(ctx, sxStart, b.y(), z, dir, up.y() - b.y(), stairs)) built++;
                    }
                }
                ctx.op(built + " stair runs climb the retaining walls");
                // doors: each house opens toward the promenade (south side)
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    doorAlong(ctx, v.cx(), v.y0(), v.cz(), 2, v.d(), compOf[i]);
                }
                // ravine bridges
                int bridges = 0;
                if (rav != null) {
                    for (Link l : m.links()) {
                        if (!l.enabled()) continue;
                        Bench b = bs.get(l.b());
                        int bc = ctx.comps.add("Ravine bridge, terrace " + (b.k() + 1), "bridge", Material.TIMBER, true, false);
                        int zb = b.zHi() - 3;
                        for (int x = rav[0] - 1; x <= rav[1]; x++)
                            for (int dz = 0; dz < 2; dz++) {
                                byte cur = g.get(x, b.y(), zb + dz);
                                if (cur == Cell.EMPTY || cur == Cell.MARK_B) g.set(x, b.y(), zb + dz, Cell.BRIDGE, bc);
                                if (Cell.isSolid(g.get(x, b.y() + 1, zb + dz)) || g.get(x, b.y() + 1, zb + dz) == Cell.MARK_B)
                                    g.set(x, b.y() + 1, zb + dz, Cell.EMPTY, bc);
                            }
                        bridges++;
                    }
                }
                ctx.op(bridges + " bridges across the ravine");
                // lookout balcony cantilevered from the top bench over the cliff
                Bench top = bs.get(bs.size() - 1);
                int lc = ctx.comps.add("Lookout balcony", "terrace", Material.SANDSTONE, true, false);
                int bx = rav != null ? rav[0] - 8 : m.sx() / 2 - 3;
                for (int x = bx; x < bx + 5; x++)
                    for (int z = top.zHi(); z < top.zHi() + 3; z++)
                        if (g.get(x, top.y(), z) == Cell.EMPTY) g.set(x, top.y(), z, Cell.TERRACE, lc);
                for (int x : new int[]{bx, bx + 4})
                    for (int y = top.y() - 1; y > 0; y--) {
                        byte b = g.get(x, y, top.zHi() + 2);
                        if (b == Cell.EMPTY) g.set(x, y, top.zHi() + 2, Cell.SUPPORT, lc);
                        else {
                            if (b == Cell.PATH || b == Cell.TERRACE) g.set(x, y, top.zHi() + 2, Cell.SUPPORT, lc); // footing through the paving
                            break;
                        }
                    }
                ctx.entrance(4, QUAY, bs.get(0).zHi());
                ctx.op("entrance on the quay");
            });

        ctx.stage("growth", "Rule-driven growth", "rules",
            "Rules choose small deep windows, plant the retaining walls and grow roof gardens.",
            () -> ctx.runProgram("growth", defaultProgram("growth", p)));

        ctx.stage("validation", "Constraint checks", "validation",
            "Walk from the quay to every house; check the ravine stays open, headroom and support.", () -> {
                standardValidation(ctx, true, 3, 8, 14);
                if (rav != null) {
                    int blocked = 0;
                    int zFrom = bs.get(bs.size() - 1).zLo(), zTo = bs.size() > 1 ? bs.get(1).zHi() : zFrom;
                    for (int z = zFrom; z < zTo; z++)
                        for (int y = QUAY + 1; y < m.sy(); y++)
                            for (int x = rav[0]; x < rav[1]; x++) {
                                byte s = g.get(x, y, z);
                                if (Cell.isSolid(s) && s != Cell.WATER) blocked++;
                            }
                    ctx.report.add("ravine", "Ravine left open", "hard", blocked == 0 ? ConstraintReport.Status.PASS : ConstraintReport.Status.FAIL,
                        blocked == 0 ? "only bridge decks cross the gorge" : blocked + " solid cells intrude into the gorge", blocked);
                }
            });

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Olive trees, potted plants and street lamps.", () -> {
                ctx.runProgram("detailing", defaultProgram("detailing", p));
                var conn = studio.forma.engine.arch.Validator.connectivity(g, ctx.comps, ctx.entranceIndices());
                ctx.op(String.format("re-check after detailing: %d of %d walkable cells reachable", conn.reachable(), conn.standable()));
            });
    }

    /** A stair against a retaining wall, rising {@code rise} cells; a landing and stone fill finish it. */
    private boolean climb(GenContext ctx, int x, int y, int z, int dir, int rise, int comp) {
        Grid g = ctx.grid;
        int dx = dir == 0 ? 1 : -1;
        for (int i = 0; i < rise; i++) {
            byte cur = g.get(x + dx * i, y + i, z);
            if (!(cur == Cell.EMPTY || cur == Cell.PATH)) return false;
        }
        if (!Cell.isStandable(g.get(x - dx, y, z))) return false;
        ctx.kit.stairRun(x, y, z, dir, rise, comp, true, y);
        int lx = x + dx * rise;
        g.set(lx, y + rise, z, Cell.TERRACE, comp);
        for (int yy = y + rise - 1; yy >= y; yy--) {
            byte b = g.get(lx, yy, z);
            if (b == Cell.EMPTY || b == Cell.PATH) g.set(lx, yy, z, Cell.WALL, comp);
        }
        return true;
    }

    // ---- refinement -------------------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        for (int i = 0; i < probe.volumes().size(); i++) {
            Volume v = probe.volumes().get(i);
            if (v.role().equals("house")) {
                rp.movable.add(i);
                rp.resizable.add(i);
                rp.terraceToggle.add(i);
            }
        }
        for (int i = 0; i < probe.links().size(); i++) rp.linkToggle.add(i);
        rp.minStoreys = 1;
        rp.maxStoreys = p.i("storeys") + 1;
        int sx = probe.sx(), sz = probe.sz();
        int rise = p.i("rise");
        List<Bench> bs = benches(p, sz);
        int[] rav = ravine(p, sx);
        rp.objective("circulation", "Connected circulation", "Every upper terrace should have a built ravine crossing.",
            m -> {
                if (rav == null) return 0;
                int missing = 0;
                for (Link l : m.links()) if (!l.enabled()) missing++;
                return missing * 0.6;
            });
        rp.objective("daylight", "Views and daylight", "A house should not rise so far above its bench that it blocks the houses behind.",
            m -> {
                double pen = 0;
                for (Volume v : m.volumes())
                    if (v.role().equals("house")) pen += Math.max(0, v.storeys() * 2 - rise - 1) * 0.25;
                return pen;
            });
        rp.objective("proportion", "Balanced proportions", "Houses close to as tall as they are wide.",
            m -> Objectives.slenderness(m, Objectives.role("house"), 0.9));
        rp.objective("void", "Open ravine and promenades", "Houses keep clear of the promenade along each bench front.",
            m -> {
                double pen = 0;
                for (Volume v : m.volumes()) {
                    if (!v.role().equals("house")) continue;
                    for (Bench b : bs) if (v.y0() == b.y() && v.z1() > b.zHi() - 3) pen += 0.5;
                }
                return pen;
            });
        rp.objective("silhouette", "Cascading silhouette", "Taller houses toward the top of the cliff.",
            m -> {
                int inv = 0, pairs = 0;
                List<Volume> hs = new ArrayList<>();
                for (Volume v : m.volumes()) if (v.role().equals("house")) hs.add(v);
                for (Volume a : hs) for (Volume b : hs) if (a.y0() < b.y0()) { pairs++; if (a.storeys() > b.storeys()) inv++; }
                return pairs == 0 ? 0 : 2.0 * inv / pairs;
            });
        rp.objective("variety", "Controlled variety", "House heights vary around 35%.", m -> Objectives.variety(m, Objectives.role("house"), 0.35));
        rp.objective("greenery", "Greenery", "About 40% of roofs are terraces with gardens.", m -> Objectives.greenery(m, Objectives.role("house"), 0.4));
        rp.objective("density", "Density", "Houses cover about 45% of the town's footprint.",
            m -> Objectives.density(m, Objectives.role("house"), (double) sx * (bs.get(0).zHi() - bs.get(bs.size() - 1).zLo()), 0.45));
        rp.hard("site bounds", m -> Objectives.withinGrid(m, 2));
        rp.hard("houses on their terrace", m -> {
            for (Volume v : m.volumes()) {
                if (!v.role().equals("house")) continue;
                Bench on = null;
                for (Bench b : bs) if (b.y() == v.y0()) on = b;
                if (on == null || v.z0() < on.zLo() || v.z1() > on.zHi() - 2) return v.name() + " would leave its terrace";
                if (rav != null && v.x1() > rav[0] - 1 && v.x0() < rav[1] + 1) return v.name() + " would fall into the ravine";
                if (inStairZone(on.k(), v.x0(), v.x1(), sx, rav, rise, bs.size())) return v.name() + " would block a stair";
            }
            return null;
        });
        rp.hard("houses apart", m -> {
            List<Volume> vs = m.volumes();
            for (int i = 0; i < vs.size(); i++)
                for (int j = i + 1; j < vs.size(); j++) {
                    Volume a = vs.get(i), b = vs.get(j);
                    if (a.y0() != b.y0()) continue;
                    if (a.x0() < b.x1() + 1 && b.x0() < a.x1() + 1 && a.z0() < b.z1() && b.z0() < a.z1()) return a.name() + " collides with " + b.name();
                }
            return null;
        });
        return rp;
    }
}
