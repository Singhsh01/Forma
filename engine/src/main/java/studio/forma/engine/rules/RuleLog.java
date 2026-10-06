package studio.forma.engine.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what rule programs actually did: per rule, how often it fired and one captured
 * before/after neighbourhood of a real application; per node, how many steps it ran.
 * This is the data behind the studio's rule inspector.
 */
public final class RuleLog {
    public static final class RuleRecord {
        public final String stage, node, nodeType, ruleId, description, input, output, symmetry;
        public final double p;
        public final int variants;
        public long applications;
        public int firstStep = -1, lastStep = -1;
        public Sample sample;

        RuleRecord(String stage, String node, String nodeType, Rule r) {
            this.stage = stage;
            this.node = node;
            this.nodeType = nodeType;
            this.ruleId = r.id;
            this.description = r.description;
            this.input = r.input;
            this.output = r.output;
            this.symmetry = r.symmetry.name().toLowerCase();
            this.p = r.p;
            this.variants = r.variants.size();
        }
    }

    /** A small box around one application: states before and after, layered like the grid. */
    public record Sample(int x, int y, int z, int nx, int ny, int nz, byte[] before, byte[] after, int variant) {}

    public record NodeRecord(String stage, String node, String type, int steps, boolean exhausted) {}

    private final Map<String, RuleRecord> rules = new LinkedHashMap<>();
    private final List<NodeRecord> nodes = new ArrayList<>();
    private String stage = "";
    private int globalStep;

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }

    RuleRecord record(String node, String nodeType, Rule r) {
        String key = stage + "|" + node + "|" + r.id;
        return rules.computeIfAbsent(key, k -> new RuleRecord(stage, node, nodeType, r));
    }

    void applied(RuleRecord rec) {
        rec.applications++;
        if (rec.firstStep < 0) rec.firstStep = globalStep;
        rec.lastStep = globalStep;
    }

    void tick() {
        globalStep++;
    }

    void node(String node, String type, int steps, boolean exhausted) {
        nodes.add(new NodeRecord(stage, node, type, steps, exhausted));
    }

    public List<RuleRecord> rules() {
        return Collections.unmodifiableList(new ArrayList<>(rules.values()));
    }

    public List<NodeRecord> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public long applicationsOf(String ruleId) {
        long n = 0;
        for (RuleRecord r : rules.values()) if (r.ruleId.equals(ruleId)) n += r.applications;
        return n;
    }
}
