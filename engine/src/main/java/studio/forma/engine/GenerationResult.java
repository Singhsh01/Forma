package studio.forma.engine;

import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.RoomGraph;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.History;
import studio.forma.engine.geometry.Scene;
import studio.forma.engine.presets.Params;
import studio.forma.engine.presets.Preset;
import studio.forma.engine.rules.RuleLog;

import java.util.Map;

/** Everything produced by one generation run. */
public record GenerationResult(
    GenerationConfig config,
    Preset preset,
    Params params,
    Massing massing,
    Grid grid,
    Components components,
    History history,
    RuleLog ruleLog,
    ConstraintReport report,
    Map<String, GenContext.StageNotes> stageNotes,
    Map<String, Long> stageMillis,
    Scene scene,
    RoomGraph roomGraph,
    long composeMillis,
    long realizeMillis,
    long sceneMillis,
    int ruleSteps
) {
    public long totalMillis() {
        return composeMillis + realizeMillis + sceneMillis;
    }
}
