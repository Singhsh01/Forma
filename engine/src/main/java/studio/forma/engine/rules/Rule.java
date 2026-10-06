package studio.forma.engine.rules;

import java.util.List;

/**
 * A named rewrite rule with its symmetry variants.
 *
 * @param p in {@code one} nodes: relative weight used to choose between matches of different rules;
 *          in {@code all} and {@code prl} nodes: probability that an individual match is applied.
 */
public final class Rule {
    public final String id;
    public final String description;
    public final String input;
    public final String output;
    public final double p;
    public final Symmetry symmetry;
    public final List<Pattern> variants;

    public Rule(String id, String description, String input, String output, double p, Symmetry symmetry, Legend legend) {
        if (id == null || id.isBlank()) throw new RuleValidationException("rule id is required");
        if (!(p > 0 && p <= 1)) throw new RuleValidationException("rule '" + id + "': p must be in (0, 1], was " + p);
        this.id = id;
        this.description = description == null ? "" : description;
        this.input = input;
        this.output = output;
        this.p = p;
        this.symmetry = symmetry;
        try {
            this.variants = Pattern.parse(input, output, legend).variants(symmetry);
        } catch (RuleValidationException e) {
            throw new RuleValidationException("rule '" + id + "': " + e.getMessage());
        }
    }

    public static Builder of(String id, String in, String out) {
        return new Builder(id, in, out);
    }

    public static final class Builder {
        private final String id, in, out;
        private String desc = "";
        private double p = 1.0;
        private Symmetry sym = Symmetry.FULL;

        Builder(String id, String in, String out) {
            this.id = id;
            this.in = in;
            this.out = out;
        }

        public Builder desc(String d) { this.desc = d; return this; }
        public Builder p(double p) { this.p = p; return this; }
        public Builder sym(Symmetry s) { this.sym = s; return this; }

        public Rule build(Legend legend) {
            return new Rule(id, desc, in, out, p, sym, legend);
        }
    }
}
