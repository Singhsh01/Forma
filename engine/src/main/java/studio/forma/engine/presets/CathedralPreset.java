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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * D. Cathedral of Light. A nave with aisles, a transept and an apse, entered between two towers.
 * The nave is one tall reserved interior under a ribbed glass vault; arcades of arches open it
 * to the aisles, clerestory windows light it, and rules grow columns that branch into the vault.
 */
public final class CathedralPreset extends BasePreset {
    static final int G = 3;

    @Override public String id() { return "cathedral"; }
    @Override public String title() { return "Cathedral of Light"; }
    @Override public String summary() {
        return "A symmetric hall of repeated arches and branching columns, with light falling through a dominant nave.";
    }

    @Override public List<String> notes() {
        return List.of("The nave interior is a reserved void: no floor or mass may intrude, so it stays one dominant space.",
            "Branching columns are grown by a diagonal rewrite rule; their reach is a parameter, not hand placement.",
            "The glass vault is a light opening for presentation; no structural claim is made for it.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("bays", "Nave bays", "Number of repeated arcade bays along the nave.", 5, 11, 8, "massing"),
            ParamSpec.integer("height", "Nave height", "Height of the nave in storeys.", 4, 9, 6, "massing"),
            ParamSpec.integer("aisle", "Aisle width", "Width of each side aisle in cells.", 3, 6, 4, "massing"),
            ParamSpec.toggle("transept", "Transept", "Cross the nave with a transept and a lantern over the crossing.", true, "massing"),
            ParamSpec.integer("towers", "Facade towers", "Towers at the entrance front.", 0, 2, 2, "massing"),
            ParamSpec.toggle("symmetry", "Mirror symmetry", "Mirror every rule and the plan about the nave axis.", true, "massing"),
            ParamSpec.real("branching", "Branching", "How many columns branch, and how far the branches reach into the vault.", 0, 1, 0.05, 0.6, "detail"),
            ParamSpec.real("light", "Light", "Share of clerestory bays glazed and of the vault left open to the sky.", 0.2, 1, 0.05, 0.75, "detail"));
    }

    @Override public List<String> programIds() {
        return List.of("growth", "detailing");
    }

    @Override public String defaultProgram(String id, Params p) {
        double br = p.get("branching"), light = p.get("light");
        String sym = p.b("symmetry") ? "mirror" : "full";
        return switch (id) {
            case "growth" -> String.format(Locale.ROOT, """
                sequence growth
                  prl clerestory steps=1
                    rule glaze "1 1" -> "N N" p=%.2f sym=none     # high bays become clerestory windows
                  prl solid steps=1
                    rule close "1" -> "W" sym=none
                  prl capitals steps=1
                    rule crown "C K" -> "C t" p=%.2f sym=none    # columns grow a crown cell into the void
                  one branches steps=%d
                    rule branch "tK KK" -> "** *t" p=0.9 sym=%s  # crowns branch diagonally upward and outward
                  prl buttress-seeds steps=1
                    rule seed "#E EE" -> "*P **" p=0.35 sym=%s   # buttress footings against the aisle walls
                """, 0.35 + 0.65 * light, 0.3 + 0.7 * br, (int) (20 + 900 * br), sym, sym);
            case "detailing" -> String.format(Locale.ROOT, """
                sequence detailing
                  prl candles steps=1
                    rule candle "F A" -> "* l" p=0.04 sym=none     # lights on the church floor
                  prl hedges steps=1
                    rule hedge "G E" -> "* V" p=0.25 sym=none
                  prl cloister-trees steps=1
                    rule tree "G E E" -> "* t V" p=0.05 sym=none
                  prl lamps steps=1
                    rule lamp "p E" -> "* l" p=%.3f sym=none
                """, 0.02 + 0.03 * light);
            default -> throw new IllegalArgumentException("unknown program " + id);
        };
    }

    static int bayLen() { return 4; }

    @Override public int[] gridSize(Params p) {
        int len = p.i("bays") * bayLen() + 20;
        int sz = Math.max(64, len + 18);
        int sy = Math.max(44, G + 2 * p.i("height") + 26);
        return new int[]{60, sy, sz};
    }

    @Override public double[] camera(Params p) {
        return new double[]{0, 14, 0, 110, -62, 36};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("night", false, "perspective", 0);
    }

    // ---- composition ------------------------------------------------------------------------

    @Override
    public Massing compose(Params p, Rng rng) {
        int[] size = gridSize(p);
        int cx = size[0] / 2;
        int bays = p.i("bays"), H = p.i("height"), aw = p.i("aisle");
        int nw = 10;
        int len = bays * bayLen();
        int z0 = (size[2] - len) / 2 + 4;
        int czNave = z0 + len / 2;
        boolean sym = p.b("symmetry");
        List<Volume> vs = new ArrayList<>();
        vs.add(new Volume("Nave", "nave", Shape.BOX, cx, czNave, nw, len, G, H, false, 0, false, 0, false));
        int ah = Math.max(2, H / 2);
        // aisles sit flush against the nave walls: west x1 == nave.x0, east x0 == nave.x1
        vs.add(new Volume("West aisle", "aisle", Shape.BOX, cx - nw / 2 - (aw - aw / 2), czNave, aw, len, G, ah, false, 0, false, 0, false));
        vs.add(new Volume("East aisle", "aisle", Shape.BOX, cx + nw / 2 + aw / 2, czNave, aw, len, G, sym ? ah : ah + (rng.nextDouble() < 0.5 ? 1 : 0), false, 0, false, 1, false));
        if (p.b("transept")) {
            int tw = 10, tl = nw + 2 * aw + 12;
            int tz = z0 + Math.max(tw / 2 + 2, len / 4);
            vs.add(new Volume("Transept", "transept", Shape.BOX, cx, tz, tl, tw, G, H, false, 0, false, 0, false));
            vs.add(new Volume("Crossing lantern", "lantern", Shape.BOX, cx, tz, 8, 8, G + 2 * H + 1, 3, false, 0, false, 0, false));
        }
        vs.add(new Volume("Apse", "apse", Shape.ROUND, cx, z0, nw + 2, 0, G, Math.max(3, H - 1), false, 0, false, 0, false));
        int towers = p.i("towers");
        int tzFront = z0 + len + 3;
        int th = H + 4 + (int) Math.round(rng.nextDouble() * 2);
        if (towers == 2) {
            vs.add(new Volume("West tower", "tower", Shape.BOX, cx - nw / 2 - 3, tzFront, 7, 7, G, th, false, 0, true, 0, false));
            vs.add(new Volume("East tower", "tower", Shape.BOX, cx + nw / 2 + 3, tzFront, 7, 7, G, sym ? th : th + 2, false, 0, true, 1, false));
        } else if (towers == 1) {
            vs.add(new Volume("Bell tower", "tower", Shape.BOX, sym ? cx : cx + nw / 2 + 5, tzFront + (sym ? 3 : 0), 8, 8, G, th + 2, false, 0, true, 0, false));
        }
        List<Massing.Void> voids = List.of(new Massing.Void("Nave interior", cx, czNave, nw / 2.0 - 1, G + 1, G + 2 * H));
        return new Massing(size[0], size[1], size[2], G, vs, List.of(), voids);
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        int[] compOf = new int[m.volumes().size()];
        Volume nave = m.volumes().get(m.indexOfRole("nave"));
        int H = nave.storeys();
        int naveTop = nave.top();
        double light = p.get("light");
        ctx.expectStages(7);

        ctx.stage("site", "Plinth and close", "constructive",
            "A stone plinth with a cloister garden around the church and a paved forecourt at the entrance.", () -> {
                int site = ctx.comps.add("Plinth", "terrain", Material.ROCK);
                ctx.history.setFrameBudget(3000);
                for (int z = 2; z < m.sz() - 2; z++)
                    for (int x = 2; x < m.sx() - 2; x++)
                        for (int y = 0; y < G; y++) g.set(x, y, z, Cell.TERRAIN, site);
                ctx.op("stone plinth for the church and its close");
            });

        ctx.stage("masses", "Nave, aisles, transept, apse and towers", "constructive",
            "Rasterise the hall: the nave as one tall reserved interior, aisles and transept as lower shells, towers with spires.", () -> {
                // reserve the nave void first so no other mass can take it
                for (int z = nave.z0() + 1; z < nave.z1() - 1; z++)
                    for (int x = nave.x0() + 1; x < nave.x1() - 1; x++)
                        for (int y = G + 1; y < naveTop; y++) g.set(x, y, z, Cell.KEEP, 0);
                int ti = m.indexOfRole("transept");
                Volume transept = ti >= 0 ? m.volumes().get(ti) : null;
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    Material mat = switch (v.role()) {
                        case "tower" -> Material.SANDSTONE;
                        case "lantern" -> Material.MARBLE;
                        default -> Material.MARBLE;
                    };
                    compOf[i] = ctx.comps.add(v.name(), v.role(), mat, true, false);
                    ctx.comps.setGlass(compOf[i], true);
                    switch (v.role()) {
                        case "nave" -> hall(ctx, v, v::covers, compOf[i]);
                        case "transept" -> hall(ctx, v, (x, z) -> v.covers(x, z) && !nave.covers(x, z), compOf[i]);
                        case "lantern" -> lantern(ctx, v, compOf[i]);
                        default -> {
                            // clear the nave's reserved void out of the footprint so the shells meet it with walls, not floors
                            boolean aisle = v.role().equals("aisle");
                            boolean apse = v.role().equals("apse");
                            ctx.kit.shell(v, (x, z) -> v.covers(x, z) && !nave.covers(x, z) && !(aisle && transept != null && transept.covers(x, z))
                                    && !(apse && z >= nave.z0()), compOf[i], Cell.ROOF, 4);
                            if (v.role().equals("tower")) {
                                belfry(ctx, v, compOf[i]);
                                spire(ctx, v, compOf[i]);
                            } else if (v.role().equals("apse")) {
                                for (int k = 1; k <= 2; k++)
                                    for (int z = v.z0(); z < v.z1(); z++)
                                        for (int x = v.x0(); x < v.x1(); x++) {
                                            double r = v.w() / 2.0 - 1.4 * k;
                                            if (!nave.covers(x, z) && Math.hypot(x + 0.5 - v.cx(), z + 0.5 - v.cz()) <= r) g.set(x, v.top() + k, z, Cell.ROOF, compOf[i]);
                                        }
                            }
                        }
                    }
                }
                // pitched glass vaults between stone ribs over nave and transept (presentation)
                glassVault(ctx, nave, nave::covers, compOf[0], light);
                int ti2 = m.indexOfRole("transept");
                if (ti2 >= 0) {
                    Volume t = m.volumes().get(ti2);
                    glassVault(ctx, t, (x, z) -> t.covers(x, z) && !nave.covers(x, z), compOf[ti2], light);
                }
                ctx.op("nave of " + p.i("bays") + " bays, " + H + " storeys tall, kept open as one interior, under a ribbed glass vault");
            });

        ctx.stage("circulation", "Arcades, portal and processional route", "constructive",
            "Cut the arcades between nave and aisles, the west portal and the doors from the towers and transept.", () -> {
                int close = ctx.comps.add("Cathedral close", "garden", Material.ROCK, false, false);
                int paths = ctx.comps.add("Forecourt", "path", Material.MARBLE, true, false);
                for (int z = 2; z < m.sz() - 2; z++)
                    for (int x = 2; x < m.sx() - 2; x++) {
                        if (g.get(x, G, z) != Cell.EMPTY) continue;
                        boolean axis = Math.abs(x + 0.5 - m.sx() / 2.0) < 4 && z > nave.z1();
                        boolean border = x < 5 || x >= m.sx() - 5 || z < 5 || z >= m.sz() - 5;
                        boolean apron = false;
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (Cell.isSolid(g.get(x + d[0], G, z + d[1])) || g.get(x + d[0], G, z + d[1]) == Cell.FLOOR || g.get(x + d[0], G, z + d[1]) == Cell.DOOR) apron = true;
                        boolean paved = axis || border || apron;
                        g.set(x, G, z, paved ? Cell.PATH : Cell.GRASS, paved ? paths : close);
                    }
                int bay = bayLen();
                int arcH = Math.max(2, 2 * (H / 2) - 1);
                for (int z = nave.z0() + 2; z < nave.z1() - 2; z++) {
                    boolean pier = (z - nave.z0()) % bay == 0;
                    for (int side = 0; side < 2; side++) {
                        int x = side == 0 ? nave.x0() : nave.x1() - 1;
                        for (int y = G; y < G + arcH; y++) {
                            if (pier) {
                                g.set(x, y, z, Cell.COLUMN, compOf[0]);
                            } else if (y == G) {
                                g.set(x, y, z, Cell.FLOOR, compOf[0]);
                            } else if (y == G + arcH - 1) {
                                g.set(x, y, z, Cell.ARCH, compOf[0]);
                            } else {
                                g.set(x, y, z, Cell.AIR, compOf[0]);
                            }
                        }
                    }
                }
                // the aisle side of each arcade opening is cut the same way (piers stay solid)
                for (int z = nave.z0() + 2; z < nave.z1() - 2; z++) {
                    boolean pier = (z - nave.z0()) % bay == 0;
                    for (int x : new int[]{nave.x0() - 1, nave.x1()}) {
                        if (g.get(x, G, z) == Cell.EMPTY || g.get(x, G, z) == Cell.GRASS || g.get(x, G, z) == Cell.PATH) continue;
                        for (int y = G; y < G + arcH; y++) {
                            byte cur = g.get(x, y, z);
                            if (pier || !(cur == Cell.WALL || cur == Cell.MARK_A)) continue;
                            g.set(x, y, z, y == G ? Cell.FLOOR : Cell.AIR, g.comp(x, y, z));
                        }
                    }
                }
                // crossing and apse open into the nave; aisles open into the transept arms
                int ti = m.indexOfRole("transept");
                if (ti >= 0) {
                    Volume t = m.volumes().get(ti);
                    int openTop = G + 2 * t.storeys() - 3;
                    for (int z = t.z0() + 1; z < t.z1() - 1; z++)
                        for (int y = G + 1; y <= openTop; y++)
                            for (int x : new int[]{nave.x0(), nave.x1() - 1, nave.x0() - 1, nave.x1()}) {
                                byte cur = g.get(x, y, z);
                                if (cur == Cell.WALL || cur == Cell.MARK_A || cur == Cell.COLUMN || cur == Cell.ARCH)
                                    g.set(x, y, z, y == openTop ? Cell.ARCH : Cell.AIR, g.comp(x, y, z));
                            }
                    for (int z = t.z0() + 1; z < t.z1() - 1; z++)
                        for (int x : new int[]{nave.x0(), nave.x1() - 1, nave.x0() - 1, nave.x1()})
                            if (g.get(x, G, z) == Cell.WALL || g.get(x, G, z) == Cell.MARK_A || g.get(x, G, z) == Cell.COLUMN) g.set(x, G, z, Cell.FLOOR, g.comp(x, G, z));
                    for (int i = 0; i < m.volumes().size(); i++) {
                        Volume a = m.volumes().get(i);
                        if (!a.role().equals("aisle")) continue;
                        doorAlong(ctx, a.cx(), G, t.z0() - 2, 2, 5, compOf[i]);
                        doorAlong(ctx, a.cx(), G, t.z1() + 1, 3, 5, compOf[i]);
                    }
                }
                int ai = m.indexOfRole("apse");
                if (ai >= 0) {
                    Volume a = m.volumes().get(ai);
                    int openTop = G + 2 * a.storeys() - 2;
                    for (int x = nave.x0() + 1; x < nave.x1() - 1; x++)
                        for (int y = G; y <= openTop; y++)
                            for (int z : new int[]{nave.z0(), nave.z0() - 1}) {
                                byte cur = g.get(x, y, z);
                                if (cur == Cell.WALL || cur == Cell.MARK_A)
                                    g.set(x, y, z, y == G ? Cell.FLOOR : y == openTop ? Cell.ARCH : Cell.AIR, g.comp(x, y, z));
                            }
                }
                // a row of free-standing columns down the nave, under the vault, ready to branch
                for (int z = nave.z0() + 3; z < nave.z1() - 3; z += bay) {
                    for (int x : new int[]{nave.x0() + 2, nave.x1() - 3}) {
                        for (int y = G + 1; y < G + 1 + Math.max(3, 2 * H - 6); y++) g.set(x, y, z, Cell.COLUMN, compOf[0]);
                    }
                }
                // west portal (facade at the high-z end) and doors
                int pz = nave.z1() - 1;
                for (int x = nave.cx() - 2; x < nave.cx() + 2; x++) {
                    g.set(x, G, pz, Cell.DOOR, compOf[0]);
                    for (int y = G + 1; y < G + 4; y++) g.set(x, y, pz, y == G + 3 ? Cell.ARCH : Cell.AIR, compOf[0]);
                }
                // rose window above the portal
                double rc = Math.min(4.2, H * 0.7);
                int ry = G + 2 * H - (int) Math.ceil(rc) - 2;
                for (int y = ry - 5; y <= ry + 5; y++)
                    for (int x = nave.cx() - 5; x <= nave.cx() + 5; x++) {
                        double d = Math.hypot(x + 0.5 - nave.cx(), y - ry);
                        if (d <= rc && g.get(x, y, pz) == Cell.WALL) g.set(x, y, pz, Cell.WINDOW, compOf[0]);
                    }
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    if (v.role().equals("tower")) doorAlong(ctx, v.cx(), G, v.cz(), 2, 6, compOf[i]);
                    if (v.role().equals("transept")) {
                        doorAlong(ctx, v.x0() + 1, G, v.cz(), 1, 4, compOf[i]);
                        doorAlong(ctx, v.x1() - 2, G, v.cz(), 0, 4, compOf[i]);
                        // the transept opens fully into the nave at the crossing
                        for (int z = v.z0() + 1; z < v.z1() - 1; z++)
                            for (int y = G + 1; y < G + 2 * v.storeys() - 1; y++)
                                for (int x : new int[]{nave.x0(), nave.x1() - 1}) if (g.get(x, y, z) == Cell.WALL || g.get(x, y, z) == Cell.MARK_A) g.set(x, y, z, Cell.AIR, compOf[0]);
                    }
                    if (v.role().equals("aisle")) for (int z = v.z0() + 3; z < v.z1() - 3; z += 6) doorAlong(ctx, v.cx(), G, z, v.cx() < nave.cx() ? 1 : 0, 6, compOf[i]);
                }
                ctx.entrance(nave.cx(), G, m.sz() - 6);
                ctx.op("arcades of " + p.i("bays") + " arches each side, a west portal under a rose window");
            });

        ctx.stage("growth", "Rule-driven growth", "rules",
            "Clerestory bays are glazed, columns crown and branch into the vault, buttress footings appear against the aisles.",
            () -> ctx.runProgram("growth", defaultProgram("growth", p)));

        ctx.stage("validation", "Constraint checks", "validation",
            "The nave must remain one open interior; every chapel and tower must be reachable from the portal.", () -> {
                // the reservation did its job during massing and growth; the nave is an enclosed interior from here on
                for (int z = nave.z0(); z < nave.z1(); z++)
                    for (int x = nave.x0(); x < nave.x1(); x++)
                        for (int y = G; y <= naveTop; y++) if (g.get(x, y, z) == Cell.KEEP) g.set(x, y, z, Cell.AIR, 0);
                standardValidation(ctx, true, 3, 10, 14);
                int intrusions = 0;
                for (int z = nave.z0() + 1; z < nave.z1() - 1; z++)
                    for (int x = nave.x0() + 1; x < nave.x1() - 1; x++)
                        for (int y = G + 1; y < naveTop - 1; y++) {
                            byte s = g.get(x, y, z);
                            if (s == Cell.WALL || s == Cell.FLOOR || s == Cell.ROOF) intrusions++;
                        }
                ctx.report.add("nave", "Dominant interior space", "hard", intrusions == 0 ? ConstraintReport.Status.PASS : ConstraintReport.Status.FAIL,
                    intrusions == 0 ? "the nave is one uninterrupted interior, " + (2 * H) + " cells tall" : intrusions + " cells of mass inside the nave", intrusions);
            });

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Lights along the nave, hedges and trees in the close, lamps in the forecourt; the vault is glazed.", () -> {
                ctx.runProgram("detailing", defaultProgram("detailing", p));

            });
    }

    /** A hall: tall walls with a bay rhythm and high clerestory bays, a single floor, no intermediate storeys. */
    private void hall(GenContext ctx, Volume v, studio.forma.engine.arch.Kit.Footprint fp, int comp) {
        Grid g = ctx.grid;
        int top = v.top();
        boolean alongZ = v.d() >= v.w();
        for (int z = v.z0(); z < v.z1(); z++)
            for (int x = v.x0(); x < v.x1(); x++) {
                if (!fp.covers(x, z)) continue;
                boolean edge = !fp.covers(x + 1, z) || !fp.covers(x - 1, z) || !fp.covers(x, z + 1) || !fp.covers(x, z - 1);
                if (!edge) {
                    if (g.get(x, G, z) == Cell.EMPTY) g.set(x, G, z, Cell.FLOOR, comp);
                    continue;
                }
                boolean pier = alongZ ? (z - v.z0()) % bayLen() == 0 : (x - v.x0()) % bayLen() == 0;
                boolean longSide = alongZ ? (!fp.covers(x + 1, z) || !fp.covers(x - 1, z)) : (!fp.covers(x, z + 1) || !fp.covers(x, z - 1));
                for (int y = G; y < top; y++) {
                    boolean high = y >= G + 2 * Math.max(1, v.storeys() / 2) + 1 && y < top - 1;
                    if (g.get(x, y, z) != Cell.EMPTY && g.get(x, y, z) != Cell.KEEP) continue;
                    g.set(x, y, z, longSide && high && !pier ? Cell.MARK_A : Cell.WALL, comp);
                }
            }
    }

    /**
     * A pitched vault: a stepped shell of glass between stone ribs. Only the outer cell of each step is
     * built, so the vault is hollow and the nave can be seen through it; ribs run across every bay.
     */
    private void glassVault(GenContext ctx, Volume v, studio.forma.engine.arch.Kit.Footprint fp, int comp, double light) {
        Grid g = ctx.grid;
        boolean alongZ = v.d() >= v.w();
        int span = alongZ ? v.w() : v.d();
        for (int layer = 0; layer * 2 < span; layer++)
            for (int z = v.z0(); z < v.z1(); z++)
                for (int x = v.x0(); x < v.x1(); x++) {
                    if (!fp.covers(x, z)) continue;
                    int t = alongZ ? x - v.x0() : z - v.z0();
                    if (t < layer || t >= span - layer) continue;
                    boolean outer = t == layer || t == span - layer - 1 || span - 2 * layer <= 2 || layer == 0 && (t == 0 || t == span - 1);
                    if (!outer && layer > 0) continue;
                    int along = alongZ ? z - v.z0() : x - v.x0();
                    boolean rib = along % bayLen() == 0 || (layer == 0 && (t == 0 || t == span - 1));
                    boolean glass = !rib && (layer > 0 || Rng.hash01(ctx.rng.seed(), x, layer, z) < light);
                    if (layer == 0 && !outer) glass = !rib && Rng.hash01(ctx.rng.seed(), x, layer, z) < light * 0.4;
                    int y = v.top() + layer;
                    if (g.get(x, y, z) == Cell.EMPTY) g.set(x, y, z, glass ? Cell.GLASS : Cell.ROOF, comp);
                }
    }

    /** Tower walls stay solid except narrow lancets on alternate storeys and an open belfry at the top. */
    private void belfry(GenContext ctx, Volume v, int comp) {
        Grid g = ctx.grid;
        for (int s = 0; s < v.storeys(); s++) {
            int yb = v.y0() + 2 * s;
            boolean top = s == v.storeys() - 1;
            for (int z = v.z0(); z < v.z1(); z++)
                for (int x = v.x0(); x < v.x1(); x++)
                    for (int y = yb; y < yb + 2; y++) {
                        if (g.get(x, y, z) != Cell.MARK_A) continue;
                        boolean lancet = s % 3 == 1 && (x == v.cx() || z == v.cz());
                        g.set(x, y, z, top ? (y == yb + 1 ? Cell.ARCH : Cell.AIR) : lancet ? Cell.WINDOW : Cell.WALL, comp);
                    }
        }
    }

    private void lantern(GenContext ctx, Volume v, int comp) {
        Grid g = ctx.grid;
        for (int z = v.z0(); z < v.z1(); z++)
            for (int x = v.x0(); x < v.x1(); x++) {
                boolean edge = x == v.x0() || x == v.x1() - 1 || z == v.z0() || z == v.z1() - 1;
                boolean corner = (x == v.x0() || x == v.x1() - 1) && (z == v.z0() || z == v.z1() - 1);
                for (int y = v.y0(); y < v.top(); y++) {
                    if (!edge) continue;
                    g.set(x, y, z, corner ? Cell.WALL : Cell.GLASS, comp);
                }
                g.set(x, v.top(), z, Cell.ROOF, comp);
            }
        // a small stepped dome
        for (int k = 1; k <= 3; k++)
            for (int z = v.z0() + k; z < v.z1() - k; z++)
                for (int x = v.x0() + k; x < v.x1() - k; x++) g.set(x, v.top() + k, z, Cell.ROOF, comp);
        g.set(v.cx(), v.top() + 4, v.cz(), Cell.LIGHT, comp);
    }

    private void spire(GenContext ctx, Volume v, int comp) {
        Grid g = ctx.grid;
        int half = v.w() / 2;
        for (int k = 0; k <= half; k++)
            for (int rep = 0; rep < 2; rep++) {
                int y = v.top() + 2 * k + rep;
                for (int z = v.z0() + k; z < v.z1() - k; z++)
                    for (int x = v.x0() + k; x < v.x1() - k; x++) g.set(x, y, z, Cell.ROOF, comp);
            }
        g.set(v.cx(), v.top() + 2 * half + 2, v.cz(), Cell.LIGHT, comp);
    }

    // ---- refinement -------------------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        boolean sym = p.b("symmetry");
        for (int i = 0; i < probe.volumes().size(); i++) {
            Volume v = probe.volumes().get(i);
            if (v.role().equals("tower")) {
                rp.movable.add(i);
                rp.resizable.add(i);
            }
            if (v.role().equals("aisle") || v.role().equals("transept") || v.role().equals("nave")) rp.resizable.add(i);
        }
        rp.minStoreys = 2;
        rp.maxStoreys = 16;
        int cx = probe.sx() / 2;
        rp.objective("circulation", "Processional route", "Towers stay beside the entrance front, reachable from the forecourt.",
            m -> {
                Volume nave = m.volumes().get(m.indexOfRole("nave"));
                double pen = 0;
                for (Volume v : m.volumes()) if (v.role().equals("tower")) pen += Math.max(0, Math.abs(v.cz() - (nave.z1() + 3)) - 1) * 0.3;
                return pen;
            });
        rp.objective("daylight", "Light in the nave", "Aisles stay well below the nave so the clerestory can be glazed.",
            m -> {
                Volume nave = m.volumes().get(m.indexOfRole("nave"));
                double pen = 0;
                for (Volume v : m.volumes()) if (v.role().equals("aisle")) pen += Math.max(0, v.storeys() - nave.storeys() / 2.0) * 0.6;
                return pen;
            });
        rp.objective("proportion", "Gothic proportion", "The nave about 1.3 times as tall as it is wide.",
            m -> {
                Volume nave = m.volumes().get(m.indexOfRole("nave"));
                double r = nave.storeys() * 2.0 / nave.w();
                return Math.abs(r - 1.3) * 2;
            });
        rp.objective("void", "Dominant interior", "The nave is the tallest roofed volume.",
            m -> {
                Volume nave = m.volumes().get(m.indexOfRole("nave"));
                double pen = 0;
                for (Volume v : m.volumes()) if (!v.role().equals("tower") && !v.role().equals("lantern") && v != nave && v.top() >= nave.top()) pen += 1;
                return pen;
            });
        rp.objective("silhouette", "Strong silhouette", "Towers rise well above the nave.",
            m -> {
                Volume nave = m.volumes().get(m.indexOfRole("nave"));
                double pen = 0;
                for (Volume v : m.volumes()) if (v.role().equals("tower")) pen += Math.max(0, nave.top() + 6 - v.top()) * 0.2;
                return pen;
            });
        rp.objective("variety", sym ? "Symmetry" : "Controlled variety", sym ? "Mirrored elements match." : "Towers differ a little.",
            m -> {
                List<Volume> t = new ArrayList<>();
                for (Volume v : m.volumes()) if (v.role().equals("tower")) t.add(v);
                if (t.size() < 2) return 0;
                Volume a = t.get(0), b = t.get(1);
                double asym = Math.abs((a.cx() - cx) + (b.cx() - cx)) + Math.abs(a.cz() - b.cz()) + Math.abs(a.storeys() - b.storeys());
                return sym ? asym * 0.4 : Math.abs(asym - 2) * 0.3;
            });
        rp.objective("greenery", "Cloister garden", "Towers leave the close open.", m -> 0);
        rp.objective("density", "Density", "Built footprint near 30% of the site.", m -> Objectives.density(m, v -> v.y0() == G, m.sx() * m.sz(), 0.3));
        rp.hard("site bounds", m -> Objectives.withinGrid(m, 4));
        rp.hard("towers clear of the nave", m -> {
            Volume nave = m.volumes().get(m.indexOfRole("nave"));
            for (Volume v : m.volumes())
                if (v.role().equals("tower") && v.x0() < nave.x1() && nave.x0() < v.x1() && v.z0() < nave.z1() && nave.z0() < v.z1())
                    return v.name() + " would stand in the nave";
            return null;
        });
        rp.hard("towers apart", m -> Objectives.separated(m, Objectives.role("tower"), Objectives.role("tower"), 0));
        return rp;
    }
}
