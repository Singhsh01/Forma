package studio.forma.engine.rules;

import studio.forma.engine.core.Grid;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared machinery for nodes that hold rewrite rules: flattening rule variants, exact
 * in-bounds matching (patterns never wrap or hang over the grid edge), application with
 * logging, and full-grid scans.
 */
public abstract class RuleNode extends Node {
    protected final List<Rule> rules;
    protected final int stepsLimit;
    protected final Pattern[] flat;
    protected final int[] flatRule;
    protected final int[] flatVariant;
    protected int counter;
    private boolean logged;

    protected RuleNode(String name, List<Rule> rules, int stepsLimit) {
        super(name);
        if (rules == null || rules.isEmpty()) throw new RuleValidationException("node '" + name + "' has no rules");
        if (stepsLimit < 0) throw new RuleValidationException("node '" + name + "': steps must be >= 0");
        this.rules = List.copyOf(rules);
        this.stepsLimit = stepsLimit;
        List<Pattern> ps = new ArrayList<>();
        List<int[]> owners = new ArrayList<>();
        for (int r = 0; r < rules.size(); r++) {
            List<Pattern> vs = rules.get(r).variants;
            for (int v = 0; v < vs.size(); v++) {
                ps.add(vs.get(v));
                owners.add(new int[]{r, v});
            }
        }
        flat = ps.toArray(new Pattern[0]);
        flatRule = new int[flat.length];
        flatVariant = new int[flat.length];
        for (int i = 0; i < flat.length; i++) {
            flatRule[i] = owners.get(i)[0];
            flatVariant[i] = owners.get(i)[1];
        }
    }

    @Override
    public void reset() {
        counter = 0;
        logged = false;
    }

    protected boolean limitReached() {
        return stepsLimit > 0 && counter >= stepsLimit;
    }

    protected void finish(RunContext ctx) {
        if (!logged) {
            logged = true;
            ctx.log.node(name, type(), counter, !limitReached());
            if (ctx.history != null) ctx.history.mark(name);
        }
    }

    static boolean fits(Pattern p, Grid g, int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x + p.nx <= g.sx && y + p.ny <= g.sy && z + p.nz <= g.sz;
    }

    static boolean matchesAt(Pattern p, Grid g, byte[] states, int x, int y, int z) {
        int[] off = p.checkOffsets;
        int[] masks = p.checkMasks;
        int sx = g.sx, sz = g.sz;
        for (int i = 0, j = 0; i < masks.length; i++, j += 3) {
            int gi = (x + off[j]) + sx * ((z + off[j + 2]) + sz * (y + off[j + 1]));
            if ((masks[i] & (1 << states[gi])) == 0) return false;
        }
        return true;
    }

    /** Applies flat pattern {@code f} at (x,y,z); returns true if any cell changed. */
    protected boolean apply(RunContext ctx, int f, int x, int y, int z) {
        Pattern p = flat[f];
        Grid g = ctx.grid;
        Rule rule = rules.get(flatRule[f]);
        RuleLog.RuleRecord rec = ctx.log.record(name, type(), rule);
        boolean capture = rec.sample == null;
        int bx0 = 0, by0 = 0, bz0 = 0, bnx = 0, bny = 0, bnz = 0;
        byte[] before = null;
        if (capture) {
            bx0 = Math.max(0, x - 1); by0 = y; bz0 = Math.max(0, z - 1);
            int bx1 = Math.min(g.sx, x + p.nx + 1), by1 = y + p.ny, bz1 = Math.min(g.sz, z + p.nz + 1);
            bnx = bx1 - bx0; bny = by1 - by0; bnz = bz1 - bz0;
            before = snapshot(g, bx0, by0, bz0, bnx, bny, bnz);
        }
        boolean changed = false;
        for (int dy = 0; dy < p.ny; dy++)
            for (int dz = 0; dz < p.nz; dz++)
                for (int dx = 0; dx < p.nx; dx++) {
                    byte o = p.out[dx + p.nx * (dz + p.nz * dy)];
                    if (o == -1) continue;
                    int gi = g.index(x + dx, y + dy, z + dz);
                    if (g.getIndex(gi) != o) {
                        g.setIndex(gi, o);
                        changed = true;
                    }
                }
        if (changed) {
            ctx.log.applied(rec);
            if (capture) {
                byte[] after = snapshot(g, bx0, by0, bz0, bnx, bny, bnz);
                rec.sample = new RuleLog.Sample(bx0, by0, bz0, bnx, bny, bnz, before, after, flatVariant[f]);
            }
        }
        return changed;
    }

    private static byte[] snapshot(Grid g, int x0, int y0, int z0, int nx, int ny, int nz) {
        byte[] b = new byte[nx * ny * nz];
        int k = 0;
        for (int y = 0; y < ny; y++)
            for (int z = 0; z < nz; z++)
                for (int x = 0; x < nx; x++) b[k++] = g.get(x0 + x, y0 + y, z0 + z);
        return b;
    }

    /** Collects every current match as triples (flatIndex, gridIndexOfOrigin) packed in an int list. */
    protected IntList scanAll(RunContext ctx) {
        Grid g = ctx.grid;
        byte[] states = g.statesView();
        IntList out = new IntList(256);
        for (int f = 0; f < flat.length; f++) {
            Pattern p = flat[f];
            for (int y = 0; y + p.ny <= g.sy; y++)
                for (int z = 0; z + p.nz <= g.sz; z++)
                    for (int x = 0; x + p.nx <= g.sx; x++)
                        if (matchesAt(p, g, states, x, y, z)) {
                            out.add(f);
                            out.add(g.index(x, y, z));
                        }
        }
        return out;
    }

    /** Minimal growable int list (avoids boxing in hot paths). */
    public static final class IntList {
        int[] a;
        int n;

        IntList(int cap) {
            a = new int[Math.max(4, cap)];
        }

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        int get(int i) {
            return a[i];
        }

        int size() {
            return n;
        }
    }
}
