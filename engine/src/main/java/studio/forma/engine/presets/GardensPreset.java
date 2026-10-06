package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
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
 * C. Hanging Gardens. Stacks of cantilevered volumes stand around an open courtyard with a pool.
 * Every exposed roof is a planted balcony; rules grow planters on the edges, vines down the
 * facades and waterfalls from the channels into the pool.
 */
public final class GardensPreset extends BasePreset {
    static final int G = 3;

    @Override public String id() { return "gardens"; }
    @Override public String title() { return "Hanging Gardens"; }
    @Override public String summary() {
        return "Stacked, cantilevered volumes spill planted balconies and water channels around open courtyards.";
    }

    @Override public List<String> notes() {
        return List.of("Each stack level overhangs the one below; the support heuristic checks every cantilever stays within its span limit.",
            "Vines and waterfalls are grown by rules that extend downward one cell per step until they meet something.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("stacks", "Stacks", "Number of terraced stacks around the courtyard.", 3, 6, 4, "massing"),
            ParamSpec.integer("levels", "Levels", "Levels per stack.", 2, 7, 5, "massing"),
            ParamSpec.integer("overhang", "Overhang", "How far each level cantilevers past the one below.", 0, 3, 2, "massing"),
            ParamSpec.integer("courtyard", "Courtyard radius", "Radius of the open courtyard and its pool.", 4, 10, 7, "massing"),
            ParamSpec.real("vegetation", "Vegetation", "Planters, vines, trees and shrubs.", 0, 1, 0.05, 0.75, "detail"),
            ParamSpec.real("water", "Water", "How many channels spill as waterfalls.", 0, 1, 0.05, 0.5, "water"),
            ParamSpec.real("bridges", "Bridge frequency", "Share of candidate bridges between stacks that are built.", 0, 1, 0.05, 0.5, "circulation"));
    }

    @Override public List<String> programIds() {
        return List.of("growth", "detailing");
    }

    @Override public String defaultProgram(String id, Params p) {
        double veg = p.get("vegetation"), water = p.get("water");
        return switch (id) {
            case "growth" -> String.format(Locale.ROOT, """
                sequence growth
                  prl party-walls steps=1
                    rule party "i1#" -> "*W*" sym=rotate
                    rule twin "i11i" -> "*WW*" sym=rotate
                  prl windows steps=1
                    rule wide "1 1" -> "N N" p=0.62 sym=none   # garden rooms open up with tall glazing
                  prl walls steps=1
                    rule rest "1" -> "W" sym=none
                  prl planters steps=1
                    rule edge "#E TE" -> "** G*" sym=rotate      # terrace cells on a drop become planter beds
                  prl vine-seeds steps=1
                    rule seed "#E GE" -> "*V **" p=%.3f sym=rotate   # a vine starts below a planter's lip
                  one vines steps=%d
                    rule hang "#E #V" -> "*V **" sym=rotate      # and hangs one cell further down the facade
                  prl falls steps=1
                    rule spill "#E wE" -> "*w **" p=%.3f sym=rotate  # a channel spills over the edge
                  one waterfalls steps=1200
                    rule fall "#E #w" -> "*w **" sym=rotate      # water keeps falling until it meets something
                  prl garden-seeds steps=1
                    rule seed "T E" -> "G E" p=%.3f sym=none
                  one gardens steps=%d
                    rule grow "GT" -> "GG"
                """, 0.08 + 0.4 * veg, (int) (60 + 1400 * veg), 0.02 + 0.2 * water, 0.02 + 0.06 * veg, (int) (100 + 900 * veg));
            case "detailing" -> String.format(Locale.ROOT, """
                sequence detailing
                  prl trees steps=1
                    rule tree "G E E" -> "* t V" p=%.3f sym=none
                  prl shrubs steps=1
                    rule shrub "G E" -> "* V" p=%.3f sym=none
                  prl lamps steps=1
                    rule lamp "p E" -> "* l" p=0.025 sym=none
                    rule terrace-lamp "T E" -> "* l" p=0.03 sym=none
                """, 0.03 + 0.08 * veg, 0.1 + 0.35 * veg);
            default -> throw new IllegalArgumentException("unknown program " + id);
        };
    }

    @Override public int[] gridSize(Params p) {
        int sx = 2 * (int) Math.ceil(ringRadius(p) + 8.5 + 8);
        int sy = Math.max(36, G + 4 * p.i("levels") + 12);
        return new int[]{sx, sy, sx};
    }

    @Override public double[] camera(Params p) {
        return new double[]{0, 10, 0, 90, -36, 30};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("garden", false, "perspective", 0);
    }

    // ---- composition ------------------------------------------------------------------------

    /** Radius of the ring of stacks: clear of the courtyard, and wide enough that neighbouring stacks do not touch. */
    static double ringRadius(Params p) {
        int n = p.i("stacks"), over = p.i("overhang"), court = p.i("courtyard");
        double spacing = 2 * (5.5 + over) * 1.15 + 2;
        return Math.max(court + 2 + 5.5 + 1, spacing / (2 * Math.sin(Math.PI / n)));
    }

    @Override
    public Massing compose(Params p, Rng rng) {
        // neighbouring stacks must stay apart; if a draw makes two touch, widen the ring and redraw
        long seed = rng.nextLong();
        Massing m = null;
        for (int grow = 0; grow <= 6; grow++) {
            m = composeRing(p, new Rng(seed), grow);
            if (stacksTouch(m) == null) return m;
        }
        return m;
    }

    static String stacksTouch(Massing m) {
        for (Volume a : m.volumes())
            for (Volume b : m.volumes()) {
                if (a == b || a.variant() == b.variant()) continue;
                boolean overlapY = a.y0() < b.top() && b.y0() < a.top();
                if (overlapY && a.x0() < b.x1() + 1 && b.x0() < a.x1() + 1 && a.z0() < b.z1() + 1 && b.z0() < a.z1() + 1)
                    return a.name() + " touches " + b.name();
            }
        return null;
    }

    private Massing composeRing(Params p, Rng rng, int grow) {
        int[] size = gridSize(p);
        int c = size[0] / 2;
        int n = p.i("stacks"), levels = p.i("levels"), over = p.i("overhang"), court = p.i("courtyard");
        List<Volume> vs = new ArrayList<>();
        List<int[]> stackLevels = new ArrayList<>();
        double base = rng.nextDouble() * Math.PI * 2;
        for (int s = 0; s < n; s++) {
            double th = base + 2 * Math.PI * s / n;
            int w0 = 9 + rng.nextInt(3), d0 = 9 + rng.nextInt(3);
            double r = ringRadius(p) + grow;
            int cx = c + (int) Math.round(r * Math.cos(th)), cz = c + (int) Math.round(r * Math.sin(th));
            // outward direction: levels step back toward the courtyard as they rise, overhanging it
            int ox = (int) Math.signum(Math.round(Math.cos(th) * 10)), oz = (int) Math.signum(Math.round(Math.sin(th) * 10));
            int ls = Math.max(2, levels - (s % 2) - rng.nextInt(2));
            int[] idx = new int[ls];
            int y = G;
            int px = cx, pz = cz, pw = w0, pd = d0;
            for (int L = 0; L < ls; L++) {
                int storeys = L == 0 ? 2 : (L % 3 == 2 ? 1 : 2);
                // alternate the cantilever: inward then sideways, always keeping a 4x4 overlap for the core
                int sxShift = L == 0 ? 0 : (L % 2 == 1 ? -ox : oz) * Math.max(0, over - (L > 3 ? 1 : 0));
                int szShift = L == 0 ? 0 : (L % 2 == 1 ? -oz : -ox) * Math.max(0, over - (L > 3 ? 1 : 0));
                int w = Math.max(6, pw - (L > 0 && rng.chance(0.4) ? 1 : 0)), d = Math.max(6, pd - (L > 0 && rng.chance(0.4) ? 1 : 0));
                int ncx = clampShift(px + sxShift, cx, 3), ncz = clampShift(pz + szShift, cz, 3);
                idx[L] = vs.size();
                vs.add(new Volume("Stack " + (char) ('A' + s) + ", level " + (L + 1), "level", Shape.BOX, ncx, ncz, w, d, y, storeys, true, 0,
                    L > 0, s, false));
                y += storeys * 2;
                px = ncx; pz = ncz; pw = w; pd = d;
            }
            stackLevels.add(idx);
        }
        List<Link> links = new ArrayList<>();
        double bf = p.get("bridges");
        for (int s = 0; s < n; s++) {
            int[] a = stackLevels.get(s), b = stackLevels.get((s + 1) % n);
            int L = Math.min(a.length, b.length) - 2;
            if (L < 1) continue;
            Volume va = vs.get(a[L]), vb = vs.get(b[L]);
            if (Objectives.gap(va, vb) > 22) continue;
            links.add(new Link(a[L], b[L], va.y0(), "garden bridge", rng.nextDouble() < bf));
        }
        List<Massing.Void> voids = List.of(new Massing.Void("Courtyard", c, c, court, G, size[1]));
        return new Massing(size[0], size[1], size[2], G, vs, links, voids);
    }

    static int clampShift(int v, int origin, int max) {
        return Math.max(origin - max, Math.min(origin + max, v));
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        int c = m.sx() / 2;
        int court = p.i("courtyard");
        int[] compOf = new int[m.volumes().size()];
        ctx.expectStages(7);

        ctx.stage("site", "Ground, courtyard and pool", "constructive",
            "Lay the ground plane, reserve the courtyard void and sink a pool at its centre.", () -> {
                int site = ctx.comps.add("Ground", "terrain", Material.ROCK);
                int pool = ctx.comps.add("Courtyard pool", "water", Material.MARBLE);
                ctx.history.setFrameBudget(3000);
                double plinth = m.sx() / 2.0 - 1.5;
                for (int z = 0; z < m.sz(); z++)
                    for (int x = 0; x < m.sx(); x++) {
                        double d = Math.hypot(x + 0.5 - c, z + 0.5 - c);
                        if (d > plinth) continue;
                        for (int y = 0; y < G; y++) g.set(x, y, z, Cell.TERRAIN, site);
                    }
                for (int z = c - court - 1; z <= c + court + 1; z++)
                    for (int x = c - court - 1; x <= c + court + 1; x++) {
                        double d = Math.hypot(x + 0.5 - c, z + 0.5 - c);
                        if (d <= court - 2.2) {
                            g.set(x, G - 1, z, Cell.WATER, pool);
                            g.set(x, G - 2, z, Cell.WATER, pool);
                        }
                        if (d <= court - 1.5) for (int y = G + 1; y < m.sy(); y++) g.set(x, y, z, Cell.KEEP, 0);
                    }
                ctx.op("ground plane, courtyard of radius " + court + " kept open to the sky, pool at its centre");
            });

        ctx.stage("masses", "Cantilevered stacks", "constructive",
            "Rasterise every level as a storeyed shell whose roof becomes a garden terrace for the level above.", () -> {
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    Material mat = v.variant() % 3 == 0 ? Material.PLASTER : v.variant() % 3 == 1 ? Material.SANDSTONE : Material.TERRACOTTA;
                    compOf[i] = ctx.comps.add(v.name(), "level", mat, true, false);
                    ctx.kit.shell(v, v::covers, compOf[i], v.terraced() ? Cell.TERRACE : Cell.ROOF, 3);
                }
                // overhang columns under the corners of each cantilever, standing on whatever is below
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    if (v.y0() == G) continue;
                    for (int[] q : new int[][]{{v.x0(), v.z0()}, {v.x1() - 1, v.z0()}, {v.x0(), v.z1() - 1}, {v.x1() - 1, v.z1() - 1}}) {
                        int y = v.y0() - 1;
                        if (g.get(q[0], y, q[1]) != Cell.EMPTY) continue;
                        while (y >= 0 && (g.get(q[0], y, q[1]) == Cell.EMPTY || g.get(q[0], y, q[1]) == Cell.KEEP)) y--;
                        byte below = g.get(q[0], y, q[1]);
                        if (below == Cell.TERRACE || below == Cell.TERRAIN || below == Cell.ROOF || below == Cell.WALL)
                            for (int yy = y + (below == Cell.TERRACE ? 0 : 1); yy < v.y0(); yy++) g.set(q[0], yy, q[1], Cell.COLUMN, compOf[i]);
                    }
                }
                ctx.op(m.volumes().size() + " levels in " + p.i("stacks") + " stacks, overhang " + p.i("overhang"));
            });

        ctx.stage("circulation", "Cores, balconies, channels and bridges", "constructive",
            "Run a stair core up each stack, open every level onto the balcony below it, cut water channels and bridge the stacks.", () -> {
                int paths = ctx.comps.add("Courtyard paths", "path", Material.SANDSTONE, true, false);
                int lawn = ctx.comps.add("Garden ground", "garden", Material.ROCK, false, false);
                // a paved ring around the courtyard and a path to the entrance; planted ground everywhere else
                for (int z = 0; z < m.sz(); z++)
                    for (int x = 0; x < m.sx(); x++) {
                        if (g.get(x, G, z) != Cell.EMPTY || g.get(x, G - 1, z) != Cell.TERRAIN) continue;
                        double d = Math.hypot(x + 0.5 - c, z + 0.5 - c);
                        boolean ring = d <= court + 2.5;
                        boolean entry = Math.abs(x + 0.5 - c) <= 1.5 && z > c;
                        g.set(x, G, z, ring || entry ? Cell.PATH : Cell.GRASS, ring || entry ? paths : lawn);
                    }
                int water = ctx.comps.add("Water channels", "water", Material.MARBLE);
                // group levels by stack (variant id)
                for (int s = 0; s < p.i("stacks"); s++) {
                    List<Integer> lv = new ArrayList<>();
                    for (int i = 0; i < m.volumes().size(); i++) if (m.volumes().get(i).variant() == s) lv.add(i);
                    if (lv.isEmpty()) continue;
                    // core: in the overlap of all levels
                    int x0 = Integer.MIN_VALUE, x1 = Integer.MAX_VALUE, z0 = Integer.MIN_VALUE, z1 = Integer.MAX_VALUE;
                    for (int i : lv) {
                        Volume v = m.volumes().get(i);
                        x0 = Math.max(x0, v.x0() + 1); x1 = Math.min(x1, v.x1() - 1);
                        z0 = Math.max(z0, v.z0() + 1); z1 = Math.min(z1, v.z1() - 1);
                    }
                    Volume top = m.volumes().get(lv.get(lv.size() - 1));
                    if (x1 - x0 >= 2 && z1 - z0 >= 2) ctx.kit.core((x0 + x1) / 2 - 1, (z0 + z1) / 2 - 1, 2, G, top.top() + 1, compOf[lv.get(0)]);
                    // ground door toward the courtyard
                    Volume ground = m.volumes().get(lv.get(0));
                    int dx = c - ground.cx(), dz = c - ground.cz();
                    int d = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? 0 : 1) : (dz > 0 ? 2 : 3);
                    doorAlong(ctx, ground.cx(), G, ground.cz(), d, 12, compOf[lv.get(0)]);
                    doorAlong(ctx, ground.cx(), G, ground.cz(), d ^ 1, 12, compOf[lv.get(0)]);
                    // each upper level opens onto the exposed balcony of the level below, in all four directions
                    for (int k = 1; k < lv.size(); k++) {
                        Volume v = m.volumes().get(lv.get(k));
                        for (int dir = 0; dir < 4; dir++) {
                            int ex = v.cx() + AX[dir] * (v.w() / 2), ez = v.cz() + AZ[dir] * (v.d() / 2);
                            int ox = ex + AX[dir], oz = ez + AZ[dir];
                            if (g.get(ox, v.y0(), oz) == Cell.TERRACE) doorAlong(ctx, v.cx(), v.y0(), v.cz(), dir, 12, compOf[lv.get(k)]);
                        }
                    }
                    // a water channel along the courtyard-facing edge of every balcony
                    for (int k = 0; k < lv.size() - 1; k++) {
                        Volume v = m.volumes().get(lv.get(k));
                        int y = v.top();
                        int edge = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? v.x1() - 1 : v.x0()) : (dz > 0 ? v.z1() - 1 : v.z0());
                        boolean alongZ = Math.abs(dx) >= Math.abs(dz);
                        int from = alongZ ? v.z0() + 1 : v.x0() + 1, to = alongZ ? v.z1() - 1 : v.x1() - 1;
                        for (int t = from; t < to; t++) {
                            int x = alongZ ? edge : t, z = alongZ ? t : edge;
                            if (g.get(x, y, z) == Cell.TERRACE) g.set(x, y, z, Cell.WATER, water);
                        }
                    }
                }
                // bridges between stacks
                int bridges = 0;
                for (Link l : m.links()) {
                    if (!l.enabled()) continue;
                    Volume va = m.volumes().get(l.a()), vb = m.volumes().get(l.b());
                    int bc = ctx.comps.add("Garden bridge " + va.name() + " to " + vb.name(), "bridge", Material.TIMBER, true, false);
                    ctx.kit.bridge(va.cx(), va.cz(), vb.cx(), vb.cz(), l.level(), 2, bc, null);
                    bridges++;
                }
                ctx.op(bridges + " bridges between stacks; a core in every stack; channels on every balcony edge");
                ctx.entrance(c, G, m.sz() - 4);
            });

        ctx.stage("growth", "Rule-driven growth", "rules",
            "Planters on terrace edges, vines down the facades, waterfalls from the channels, gardens across every balcony.",
            () -> ctx.runProgram("growth", defaultProgram("growth", p)));

        ctx.stage("validation", "Constraint checks", "validation",
            "Walk from the courtyard to every level; the courtyard must stay open and every cantilever supported.",
            () -> standardValidation(ctx, true, 3, 9, 26));

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Trees, shrubs and lanterns.", () -> {
                ctx.runProgram("detailing", defaultProgram("detailing", p));
                var conn = studio.forma.engine.arch.Validator.connectivity(g, ctx.comps, ctx.entranceIndices());
                ctx.op(String.format("re-check after detailing: %d of %d walkable cells reachable", conn.reachable(), conn.standable()));
            });
    }

    // ---- refinement -------------------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        int c = probe.sx() / 2;
        int court = p.i("courtyard");
        for (int i = 0; i < probe.volumes().size(); i++) {
            Volume v = probe.volumes().get(i);
            if (v.mobile()) rp.movable.add(i);
            rp.terraceToggle.add(i);
        }
        for (int i = 0; i < probe.links().size(); i++) rp.linkToggle.add(i);
        rp.objective("circulation", "Connected circulation", "Neighbouring stacks are linked by at least one bridge.",
            m -> {
                int off = 0;
                for (Link l : m.links()) if (!l.enabled()) off++;
                return off * 0.4;
            });
        rp.objective("daylight", "Daylight", "Stacks keep apart from each other.", m -> Objectives.crowding(m, v -> v.y0() == G, 4));
        rp.objective("proportion", "Balanced cantilevers", "Each level overhangs the one below by about two cells, never more than four.",
            m -> {
                double pen = 0;
                for (Volume v : m.volumes()) {
                    Volume below = levelBelow(m, v);
                    if (below == null) continue;
                    double shift = Math.hypot(v.cx() - below.cx(), v.cz() - below.cz());
                    pen += Math.abs(shift - 2) * 0.15;
                }
                return pen;
            });
        rp.objective("void", "Preserved courtyard", "Upper levels overhang the courtyard a little but never crowd it.",
            m -> {
                double pen = 0;
                for (Volume v : m.volumes()) {
                    double d = Math.hypot(v.cx() - c, v.cz() - c) - Math.max(v.w(), v.d()) / 2.0;
                    if (d < court) pen += (court - d) * 0.3;
                }
                return pen;
            });
        rp.objective("silhouette", "Stepped silhouette", "Levels get no larger as they rise.",
            m -> {
                double pen = 0;
                for (Volume v : m.volumes()) {
                    Volume below = levelBelow(m, v);
                    if (below != null && v.footprintArea() > below.footprintArea()) pen += 0.4;
                }
                return pen;
            });
        rp.objective("variety", "Controlled variety", "Cantilevers turn in different directions.",
            m -> {
                int same = 0, pairs = 0;
                for (Volume v : m.volumes()) {
                    Volume b = levelBelow(m, v);
                    if (b == null) continue;
                    Volume bb = levelBelow(m, b);
                    if (bb == null) continue;
                    pairs++;
                    if (Integer.signum(v.cx() - b.cx()) == Integer.signum(b.cx() - bb.cx()) && Integer.signum(v.cz() - b.cz()) == Integer.signum(b.cz() - bb.cz())) same++;
                }
                return pairs == 0 ? 0 : (double) same / pairs;
            });
        rp.objective("greenery", "Greenery", "Nearly every roof is a garden.", m -> Objectives.greenery(m, v -> true, 0.95));
        rp.objective("density", "Density", "Stacks cover about a fifth of the site.", m -> Objectives.density(m, v -> v.y0() == G, m.sx() * m.sz(), 0.2));
        rp.hard("site bounds", m -> Objectives.withinGrid(m, 2));
        rp.hard("levels rest on the level below", m -> {
            for (Volume v : m.volumes()) {
                Volume b = levelBelow(m, v);
                if (b == null) continue;
                int ox = Math.min(v.x1(), b.x1()) - Math.max(v.x0(), b.x0());
                int oz = Math.min(v.z1(), b.z1()) - Math.max(v.z0(), b.z0());
                if (ox < 4 || oz < 4) return v.name() + " would overhang too far";
            }
            return null;
        });
        rp.hard("stacks apart", GardensPreset::stacksTouch);
        rp.hard("courtyard ground clear", m -> {
            for (Volume v : m.volumes())
                if (v.y0() == G) {
                    for (int z = v.z0(); z < v.z1(); z++)
                        for (int x = v.x0(); x < v.x1(); x++)
                            if (Math.hypot(x + 0.5 - c, z + 0.5 - c) <= court) return v.name() + " would stand in the courtyard";
                }
            return null;
        });
        rp.hard("bridges", m -> Objectives.linksWithin(m, 22));
        return rp;
    }

    static Volume levelBelow(Massing m, Volume v) {
        for (Volume o : m.volumes()) if (o.variant() == v.variant() && o.top() == v.y0()) return o;
        return null;
    }
}
