package studio.forma.engine;

import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.arch.Kit;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.History;
import studio.forma.engine.core.Rng;
import studio.forma.engine.presets.Params;
import studio.forma.engine.rules.Legend;
import studio.forma.engine.rules.Node;
import studio.forma.engine.rules.ProgramParser;
import studio.forma.engine.rules.RuleLog;
import studio.forma.engine.rules.RunContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a preset needs while realising a design: grid, components, seeded randomness,
 * history, rule log, constraint report, cancellation, progress reporting and stage bookkeeping.
 */
public final class GenContext {
    public final Grid grid;
    public final Components comps = new Components();
    public final Rng rng;
    public final History history;
    public final RuleLog log = new RuleLog();
    public final ConstraintReport report = new ConstraintReport();
    public final Cancellation cancel;
    public final Params params;
    public final Kit kit;
    public final Legend legend = Legend.standard();
    public final Map<String, String> ruleOverrides;
    public final ProgressListener progress;

    /** Per stage: the operations performed (constructive) or programs run (rules). */
    public final Map<String, StageNotes> notes = new LinkedHashMap<>();
    public final Map<String, Long> stageMillis = new LinkedHashMap<>();
    public final List<int[]> entrances = new ArrayList<>();
    /** Extra primitives for forms a coarse grid cannot express (curved shells, tilted supports), in grid coordinates. */
    public final List<Prim> prims = new ArrayList<>();
    /** Components whose walls are drawn by primitives instead of being meshed from cells. */
    public final java.util.Set<Integer> primOnly = new java.util.HashSet<>();

    public record Prim(String kind, String material, double cx, double cy, double cz, double sx, double sy, double sz,
                       double rot, double tilt, int comp) {}

    public void prim(String kind, String material, double cx, double cy, double cz, double sx, double sy, double sz,
                     double rot, double tilt, int comp) {
        prims.add(new Prim(kind, material, cx, cy, cz, sx, sy, sz, rot, tilt, comp));
    }
    private int ruleSteps;
    private final int maxRuleSteps;

    public interface ProgressListener {
        void progress(String stageId, String stageLabel, double fraction, String message);
    }

    public static final class StageNotes {
        public final String id, label, kind, description;
        public final List<String> operations = new ArrayList<>();
        public final Map<String, String> programs = new LinkedHashMap<>();
        public final Map<String, Boolean> programOverridden = new LinkedHashMap<>();

        StageNotes(String id, String label, String kind, String description) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.description = description;
        }
    }

    public GenContext(Grid grid, long seed, Params params, Map<String, String> ruleOverrides, Cancellation cancel,
                      ProgressListener progress, int maxRuleSteps) {
        this.grid = grid;
        this.rng = new Rng(seed);
        this.history = History.forGrid(grid);
        grid.setSink(history);
        this.cancel = cancel;
        this.params = params;
        this.kit = new Kit(grid);
        this.ruleOverrides = ruleOverrides == null ? Map.of() : ruleOverrides;
        this.progress = progress != null ? progress : (a, b, c, d) -> {};
        this.maxRuleSteps = maxRuleSteps;
    }

    private StageNotes current;
    private int stageCount, stageTotal = 8;

    public void expectStages(int n) {
        stageTotal = Math.max(1, n);
    }

    /** Runs one named pipeline stage with history framing, timing and progress reporting. */
    public void stage(String id, String label, String kind, String description, Runnable body) {
        cancel.check();
        current = new StageNotes(id, label, kind, description);
        notes.put(id, current);
        history.beginStage(id, label, kind, description);
        log.setStage(id);
        progress.progress(id, label, (double) stageCount / stageTotal, label);
        long t0 = System.nanoTime();
        body.run();
        history.endStage();
        stageMillis.put(id, (System.nanoTime() - t0) / 1_000_000);
        stageCount++;
        progress.progress(id, label, (double) stageCount / stageTotal, label + " done");
        current = null;
    }

    /** Records a constructive operation for the inspector (what code did, in plain words). */
    public void op(String text) {
        if (current != null) current.operations.add(text);
    }

    /**
     * Runs a rule program. If the user supplied an override for {@code programId}, that text is run
     * instead (it has already been validated by {@link #validateOverrides}).
     */
    public void runProgram(String programId, String defaultSource) {
        String src = ruleOverrides.getOrDefault(programId, defaultSource);
        boolean overridden = ruleOverrides.containsKey(programId);
        Node root = ProgramParser.parse(src, legend);
        if (current != null) {
            current.programs.put(programId, src);
            current.programOverridden.put(programId, overridden);
        }
        int budget = Math.max(1, maxRuleSteps - ruleSteps);
        try (RunContext rc = new RunContext(grid, rng.fork("rules:" + programId), log, cancel, history, budget)) {
            rc.run(root);
            ruleSteps += rc.steps();
            if (rc.hitStepLimit()) op("program '" + programId + "' stopped at the step limit");
        }
    }

    public int ruleSteps() {
        return ruleSteps;
    }

    public void entrance(int x, int y, int z) {
        entrances.add(new int[]{x, y, z});
    }

    public int[] entranceIndices() {
        int[] out = new int[entrances.size()];
        for (int i = 0; i < out.length; i++) {
            int[] e = entrances.get(i);
            out[i] = grid.index(e[0], e[1], e[2]);
        }
        return out;
    }
}
