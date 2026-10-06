package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
import studio.forma.engine.arch.ConstraintReport.Status;
import studio.forma.engine.arch.Validator;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Grid;

import java.util.ArrayList;
import java.util.List;

/** Helpers shared by presets: axis-aligned doors, standard validation, plaza fill. */
public abstract class BasePreset implements Preset {

    protected static final int[] AX = {1, -1, 0, 0};
    protected static final int[] AZ = {0, 0, 1, -1};

    /**
     * Walks from (x, y, z) along axis d, turning wall/bay/window cells into a doorway until the walk
     * leaves the masonry into a standable or open cell. Returns the number of door cells cut.
     */
    protected static int doorAlong(GenContext ctx, int x, int y, int z, int d, int maxSteps, int comp) {
        Grid g = ctx.grid;
        int cut = 0;
        boolean inWall = false;
        for (int i = 0; i < maxSteps; i++) {
            int cx = x + AX[d] * i, cz = z + AZ[d] * i;
            if (!g.inBounds(cx, y, cz)) break;
            byte s = g.get(cx, y, cz);
            if (s == Cell.WALL || s == Cell.MARK_A || s == Cell.WINDOW) {
                ctx.kit.door(cx, y, cz, comp == 0 ? g.comp(cx, y, cz) : comp);
                cut++;
                inWall = true;
            } else if (inWall) {
                break;
            }
        }
        return cut;
    }

    /** A stepped gable roof over a box volume: full slab, then narrowing ridges along the long axis. */
    protected static void gableRoof(GenContext ctx, studio.forma.engine.arch.Massing.Volume v, int comp) {
        Grid g = ctx.grid;
        boolean alongX = v.w() >= v.d();
        int span = alongX ? v.d() : v.w();
        for (int layer = 0; layer * 2 < span; layer++) {
            for (int z = v.z0(); z < v.z1(); z++)
                for (int x = v.x0(); x < v.x1(); x++) {
                    int t = alongX ? z - v.z0() : x - v.x0();
                    if (t < layer || t >= span - layer) continue;
                    int y = v.top() + layer;
                    byte cur = g.get(x, y, z);
                    if (cur == Cell.EMPTY || cur == Cell.TERRACE) g.set(x, y, z, Cell.ROOF, comp);
                }
        }
    }

    /** Fills exposed ground (terrain directly below, empty cell) at level y with walkable paving. */
    protected static int pave(GenContext ctx, int y, int comp) {
        return pave(ctx, y, comp, Cell.TERRAIN, Cell.TERRAIN);
    }

    /** Paves empty cells at level y that sit on either of the given states. */
    protected static int pave(GenContext ctx, int y, int comp, byte on1, byte on2) {
        Grid g = ctx.grid;
        int n = 0;
        for (int z = 0; z < g.sz; z++)
            for (int x = 0; x < g.sx; x++)
                if (g.get(x, y, z) == Cell.EMPTY && (g.get(x, y - 1, z) == on1 || g.get(x, y - 1, z) == on2)) {
                    g.set(x, y, z, Cell.PATH, comp);
                    n++;
                }
        return n;
    }

    /** Standard validation used by all presets; records checks into the context's report. */
    protected static void standardValidation(GenContext ctx, boolean groundedSupport, int cantilever, int slabSpan, int bridgeSpan) {
        Grid g = ctx.grid;
        var rep = ctx.report;
        int[] entr = ctx.entranceIndices();
        if (entr.length == 0) {
            rep.add("connectivity", "Circulation reaches every required space", "hard", Status.FAIL, "no entrance defined", 0);
        } else {
            Validator.Connectivity c = Validator.connectivity(g, ctx.comps, entr);
            double frac = c.standable() == 0 ? 0 : (double) c.reachable() / c.standable();
            Status st = c.unreachableRequired().isEmpty() ? Status.PASS : Status.FAIL;
            String detail = c.unreachableRequired().isEmpty()
                ? String.format("all required spaces reachable from the entrance; %.0f%% of walkable cells connected", frac * 100)
                : "unreachable: " + String.join(", ", c.unreachableRequired());
            rep.add("connectivity", "Circulation reaches every required space", "hard", st, detail, frac);
        }
        int conflicts = ctx.kit.conflicts();
        rep.add("occupancy", "No conflicting solid occupancy", "hard",
            conflicts == 0 ? Status.PASS : Status.WARN,
            conflicts == 0 ? "no element was written into another" : conflicts + " overlapping writes were refused and left the earlier element intact",
            conflicts);
        Validator.Voids v = Validator.voids(g);
        rep.add("voids", "Atriums and courtyards stay open", "hard",
            v.blockedColumns() == 0 ? Status.PASS : Status.FAIL,
            v.keepCells() == 0 ? "no reserved voids in this design" :
                v.keepCells() + " reserved cells, " + (v.blockedColumns() == 0 ? "all open to the sky" : v.blockedColumns() + " columns covered"),
            v.blockedColumns());
        List<String> ex = new ArrayList<>();
        int clear = Validator.clearanceExamples(g, ex);
        rep.add("clearance", "Headroom above outdoor circulation", "hard",
            clear == 0 ? Status.PASS : Status.WARN, clear == 0 ? "every path, stair and bridge has clear headroom"
                : clear + " walkable cells have a solid directly above (for example " + String.join("; ", ex.subList(0, Math.min(2, ex.size()))) + ")", clear);
        if (groundedSupport) {
            Validator.Support s = Validator.support(g, ctx.comps, cantilever, slabSpan, bridgeSpan);
            double frac = s.structural() == 0 ? 1 : 1 - (double) s.unsupported() / s.structural();
            rep.add("support", "Support heuristic (grounded mode)", "heuristic",
                s.unsupported() == 0 ? Status.PASS : (frac > 0.985 ? Status.WARN : Status.FAIL),
                s.unsupported() == 0 ? "every mass rests on a load path within the span limits"
                    : s.unsupported() + " cells lack a load path in " + String.join(", ", s.unsupportedComponents()) + " (e.g. " + String.join("; ", s.examples()) + ")",
                frac);
        }
        List<String> floating = new ArrayList<>();
        ctx.comps.all().forEach(c -> { if (c.floating()) floating.add(c.name()); });
        if (!floating.isEmpty())
            rep.add("floating", "Floating elements (fantasy mode)", "info", Status.WARN,
                "deliberately unsupported: " + String.join(", ", floating), floating.size());
        double sky = Validator.skyAccess(g);
        rep.add("daylight", "Open sky over outdoor walkways (daylight proxy)", "heuristic",
            sky > 0.6 ? Status.PASS : Status.WARN, String.format("%.0f%% of outdoor walking surfaces see open sky", sky * 100), sky);
        int top = 0, built = 0;
        for (int i = 0; i < g.size(); i++) {
            byte s = g.getIndex(i);
            if (s != Cell.EMPTY && s != Cell.TERRAIN && s != Cell.KEEP && s != Cell.AIR) {
                built++;
                top = Math.max(top, g.yOf(i));
            }
        }
        rep.add("height", "Height within site limit", "hard", top < g.sy - 1 ? Status.PASS : Status.FAIL,
            "highest element at level " + top + " of " + (g.sy - 1), top);
        rep.add("density", "Built density", "info", Status.PASS,
            String.format("%.1f%% of the site volume is built", 100.0 * built / g.size()), (double) built / g.size());
    }
}
