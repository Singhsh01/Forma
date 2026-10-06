package studio.forma.engine.rules;

import studio.forma.engine.core.Grid;

import java.util.List;

/**
 * {@code prl} node: each step finds all matches against the grid as it is at the start of the
 * step, then applies each match independently with probability {@code p}, in scan order
 * (y, then z, then x). Overlapping writes are resolved deterministically: the later match in
 * scan order wins. Use {@code steps} to bound repeated application.
 */
public final class ParallelNode extends RuleNode {
    public ParallelNode(String name, List<Rule> rules, int stepsLimit) {
        super(name, rules, stepsLimit);
    }

    @Override
    public String type() {
        return "prl";
    }

    @Override
    public boolean step(RunContext ctx) {
        if (limitReached()) {
            finish(ctx);
            return false;
        }
        IntList m = scanAll(ctx);
        Grid g = ctx.grid;
        boolean any = false;
        for (int i = 0; i < m.size(); i += 2) {
            int f = m.get(i), pos = m.get(i + 1);
            double prob = rules.get(flatRule[f]).p;
            if (prob < 1 && !ctx.rng.chance(prob)) continue;
            if (apply(ctx, f, g.xOf(pos), g.yOf(pos), g.zOf(pos))) any = true;
        }
        if (!any) {
            finish(ctx);
            return false;
        }
        counter++;
        return true;
    }
}
