package studio.forma.engine.rules;

import studio.forma.engine.core.Grid;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * {@code one} node (MarkovJunior's "exists" node): each step chooses one current match at
 * random and applies it.
 *
 * <p>Matches are drawn uniformly from all matches of all rules in the node; a rule's {@code p}
 * acts as a relative weight through rejection sampling. The match set is cached and refreshed
 * incrementally: before each step, positions whose footprint touches a cell changed since the
 * node last ran are re-examined; stale cached matches are discarded lazily when drawn. Because
 * the cache never holds duplicates and stale entries are rejected, the draw is exactly uniform
 * over the currently valid matches (verified against a full rescan in the tests).
 */
public final class OneNode extends RuleNode {
    private long[] list = new long[256];
    private int size;
    private final HashMap<Long, Integer> where = new HashMap<>();
    private boolean initialized;
    private int journalSeen;

    public OneNode(String name, List<Rule> rules, int stepsLimit) {
        super(name, rules, stepsLimit);
    }

    @Override
    public String type() {
        return "one";
    }

    @Override
    public void reset() {
        super.reset();
        initialized = false;
        size = 0;
        where.clear();
    }

    private static long key(int f, int pos) {
        return ((long) f << 32) | (pos & 0xffffffffL);
    }

    private void add(long k) {
        if (where.containsKey(k)) return;
        if (size == list.length) list = Arrays.copyOf(list, size * 2);
        list[size] = k;
        where.put(k, size);
        size++;
    }

    private void removeAt(int i) {
        long k = list[i];
        where.remove(k);
        size--;
        if (i != size) {
            list[i] = list[size];
            where.put(list[i], i);
        }
    }

    private void fullScan(RunContext ctx) {
        size = 0;
        where.clear();
        IntList all = scanAll(ctx);
        for (int i = 0; i < all.size(); i += 2) add(key(all.get(i), all.get(i + 1)));
        journalSeen = ctx.journalLen;
    }

    private void refresh(RunContext ctx) {
        int unseen = ctx.journalLen - journalSeen;
        if (unseen <= 0) return;
        Grid g = ctx.grid;
        if (unseen > Math.max(512, g.size() / 40)) {
            fullScan(ctx);
            return;
        }
        byte[] states = g.statesView();
        for (int j = journalSeen; j < ctx.journalLen; j++) {
            int ci = ctx.journal[j];
            int cx = g.xOf(ci), cy = g.yOf(ci), cz = g.zOf(ci);
            for (int f = 0; f < flat.length; f++) {
                Pattern p = flat[f];
                int x0 = Math.max(0, cx - p.nx + 1), x1 = Math.min(g.sx - p.nx, cx);
                int y0 = Math.max(0, cy - p.ny + 1), y1 = Math.min(g.sy - p.ny, cy);
                int z0 = Math.max(0, cz - p.nz + 1), z1 = Math.min(g.sz - p.nz, cz);
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        for (int x = x0; x <= x1; x++) {
                            int pos = g.index(x, y, z);
                            long k = key(f, pos);
                            if (!where.containsKey(k) && matchesAt(p, g, states, x, y, z)) add(k);
                        }
            }
        }
        journalSeen = ctx.journalLen;
    }

    @Override
    public boolean step(RunContext ctx) {
        if (limitReached()) {
            finish(ctx);
            return false;
        }
        if (!initialized) {
            fullScan(ctx);
            initialized = true;
        } else {
            refresh(ctx);
        }
        Grid g = ctx.grid;
        byte[] states = g.statesView();
        int rejections = 0;
        while (size > 0) {
            int k = ctx.rng.nextInt(size);
            long key = list[k];
            int f = (int) (key >>> 32);
            int pos = (int) key;
            int x = g.xOf(pos), y = g.yOf(pos), z = g.zOf(pos);
            Pattern p = flat[f];
            if (!matchesAt(p, g, states, x, y, z)) {
                removeAt(k);
                continue;
            }
            double w = rules.get(flatRule[f]).p;
            if (w < 1 && !ctx.rng.chance(w)) {
                if (++rejections > 100_000) break;
                continue;
            }
            if (!apply(ctx, f, x, y, z)) {
                removeAt(k); // applying would not change anything; it can never make progress here
                continue;
            }
            counter++;
            return true;
        }
        finish(ctx);
        return false;
    }

    /** Test hook: brings the cache up to date with the change journal. */
    void refreshForTest(RunContext ctx) {
        refresh(ctx);
    }

    /** Test hook: cached candidate keys (may include stale entries, never misses valid ones). */
    java.util.Set<Long> cachedKeysForTest() {
        return new java.util.HashSet<>(where.keySet());
    }
}
