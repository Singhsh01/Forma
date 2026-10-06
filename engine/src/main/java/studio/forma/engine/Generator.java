package studio.forma.engine;

import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.RoomGraph;
import studio.forma.engine.arch.Validator;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;
import studio.forma.engine.geometry.Scene;
import studio.forma.engine.geometry.SceneBuilder;
import studio.forma.engine.presets.Params;
import studio.forma.engine.presets.Preset;
import studio.forma.engine.presets.PresetRegistry;
import studio.forma.engine.rules.Legend;
import studio.forma.engine.rules.ProgramParser;
import studio.forma.engine.rules.RuleValidationException;

import java.util.Map;

/** Entry point: config in, finished design out. Pure, single-threaded and deterministic. */
public final class Generator {
    private Generator() {}

    public static final int MAX_RULE_STEPS = 400_000;

    public static GenerationResult generate(GenerationConfig cfg, Cancellation cancel, GenContext.ProgressListener progress) {
        Preset preset = PresetRegistry.get(cfg.preset());
        Params params = Params.resolve(preset.params(), cfg.params());
        validateOverrides(preset, cfg.ruleOverrides());
        long t0 = System.nanoTime();
        Massing m = preset.compose(params, new Rng(cfg.seed()).fork("compose"));
        long composeMs = (System.nanoTime() - t0) / 1_000_000;
        return realize(cfg, preset, params, m, cancel, progress, composeMs);
    }

    /** Realises an explicit massing (used after MCMC refinement) with the config's seed and parameters. */
    public static GenerationResult realize(GenerationConfig cfg, Preset preset, Params params, Massing m,
                                           Cancellation cancel, GenContext.ProgressListener progress, long composeMs) {
        int[] size = preset.gridSize(params);
        Grid grid = new Grid(size[0], size[1], size[2]);
        GenContext ctx = new GenContext(grid, cfg.seed(), params, cfg.ruleOverrides(),
            cancel == null ? Cancellation.none() : cancel, progress, MAX_RULE_STEPS);
        long t1 = System.nanoTime();
        preset.realize(m, ctx);
        long realizeMs = (System.nanoTime() - t1) / 1_000_000;
        grid.setSink(null);
        ctx.cancel.check();
        long t2 = System.nanoTime();
        ctx.progress.progress("geometry", "Final geometry", 0.97, "building instanced geometry");
        Scene scene = SceneBuilder.build(grid, ctx.comps, cfg.seed(), ctx.prims, ctx.primOnly);
        var conn = Validator.connectivity(grid, ctx.comps, ctx.entranceIndices());
        RoomGraph rg = RoomGraph.build(grid, ctx.comps, conn.reached());
        long sceneMs = (System.nanoTime() - t2) / 1_000_000;
        return new GenerationResult(cfg, preset, params, m, grid, ctx.comps, ctx.history, ctx.log, ctx.report,
            ctx.notes, ctx.stageMillis, scene, rg, composeMs, realizeMs, sceneMs, ctx.ruleSteps());
    }

    /** Parses every override so malformed rule text fails fast with a precise message. */
    public static void validateOverrides(Preset preset, Map<String, String> overrides) {
        if (overrides == null) return;
        for (var e : overrides.entrySet()) {
            if (!preset.programIds().contains(e.getKey()))
                throw new RuleValidationException("preset '" + preset.id() + "' has no rule program '" + e.getKey()
                    + "' (available: " + String.join(", ", preset.programIds()) + ")");
            try {
                ProgramParser.parse(e.getValue(), Legend.standard());
            } catch (RuleValidationException ex) {
                throw new RuleValidationException("program '" + e.getKey() + "', " + ex.getMessage());
            }
        }
    }
}
