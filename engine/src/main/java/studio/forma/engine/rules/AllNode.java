package studio.forma.engine.rules;

import studio.forma.engine.core.Grid;

import java.util.List;

/**
 * {@code all} node (MarkovJunior's "forall" node): each step finds every match, visits them in
 * a seeded random order and applies each one that still matches and does not overlap cells
 * already written during this step. This greedily picks a maximal set of non-conflicting
 * matches. Rule {@code p} is the probability that an individual match is attempted.
 */
public final class AllNode extends RuleNode {
    public AllNode(String name, List<Rule> rules, int stepsLimit) {
        super(name, rules, stepsLimit);
    }

    @Override
    public String type() {
        return "all";
    }

    @Override
    public boolean step(RunContext ctx) {
        if (limitReached()) {
            finish(ctx);
            return false;
        }
        IntList m = scanAll(ctx);
        int n = m.size() / 2;
        if (n == 0) {
            finish(ctx);
            return false;
        }
        // Fisher-Yates over match pairs
        for (int i = n - 1; i > 0; i--) {
            int j = ctx.rng.nextInt(i + 1);
            int a0 = m.a[2 * i], a1 = m.a[2 * i + 1];
            m.a[2 * i] = m.a[2 * j];
            m.a[2 * i + 1] = m.a[2 * j + 1];
            m.a[2 * j] = a0;
            m.a[2 * j + 1] = a1;
        }
        Grid g = ctx.grid;
        byte[] states = g.statesView();
        int stamp = ctx.nextTouchStamp();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int f = m.a[2 * i], pos = m.a[2 * i + 1];
            Pattern p = flat[f];
            double prob = rules.get(flatRule[f]).p;
            if (prob < 1 && !ctx.rng.chance(prob)) continue;
            int x = g.xOf(pos), y = g.yOf(pos), z = g.zOf(pos);
            if (!matchesAt(p, g, states, x, y, z)) continue;
            if (conflicts(p, g, ctx.touchStamp, stamp, x, y, z)) continue;
            if (apply(ctx, f, x, y, z)) any = true;
            mark(p, g, ctx.touchStamp, stamp, x, y, z);
        }
        if (!any) {
            finish(ctx);
            return false;
        }
        counter++;
        return true;
    }

    private static boolean conflicts(Pattern p, Grid g, int[] touch, int stamp, int x, int y, int z) {
        for (int dy = 0; dy < p.ny; dy++)
            for (int dz = 0; dz < p.nz; dz++)
                for (int dx = 0; dx < p.nx; dx++) {
                    if (p.out[dx + p.nx * (dz + p.nz * dy)] == -1) continue;
                    if (touch[g.index(x + dx, y + dy, z + dz)] == stamp) return true;
                }
        return false;
    }

    private static void mark(Pattern p, Grid g, int[] touch, int stamp, int x, int y, int z) {
        for (int dy = 0; dy < p.ny; dy++)
            for (int dz = 0; dz < p.nz; dz++)
                for (int dx = 0; dx < p.nx; dx++) {
                    if (p.out[dx + p.nx * (dz + p.nz * dy)] == -1) continue;
                    touch[g.index(x + dx, y + dy, z + dz)] = stamp;
                }
    }
}
