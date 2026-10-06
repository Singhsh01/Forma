package studio.forma.engine.io;

import studio.forma.engine.GenContext;
import studio.forma.engine.GenerationConfig;
import studio.forma.engine.GenerationResult;
import studio.forma.engine.Version;
import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.RoomGraph;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.History;
import studio.forma.engine.geometry.Scene;
import studio.forma.engine.mcmc.MetropolisSampler;
import studio.forma.engine.presets.ParamSpec;
import studio.forma.engine.presets.Params;
import studio.forma.engine.presets.Preset;
import studio.forma.engine.presets.PresetRegistry;
import studio.forma.engine.rules.RuleLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON contracts shared by the CLI and the HTTP API. See docs/API.md for the schemas. */
public final class Serializers {
    private Serializers() {}

    // ---------------------------------------------------------------- config

    public static void config(Json.Writer w, GenerationConfig c) {
        w.object();
        w.field("preset", c.preset());
        w.field("seed", c.seed());
        w.key("params").object();
        c.params().forEach((k, v) -> w.field(k, v));
        w.end();
        w.key("ruleOverrides").object();
        c.ruleOverrides().forEach(w::field);
        w.end();
        w.field("generatorVersion", c.generatorVersion());
        w.end();
    }

    @SuppressWarnings("unchecked")
    public static GenerationConfig parseConfig(Map<String, Object> m) {
        Object preset = m.get("preset");
        if (!(preset instanceof String ps) || ps.isBlank()) throw new IllegalArgumentException("'preset' is required");
        Object seedO = m.get("seed");
        long seed;
        if (seedO instanceof Double d) seed = (long) (double) d;
        else if (seedO instanceof String s) {
            try {
                seed = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'seed' must be an integer");
            }
        } else if (seedO == null) seed = 1;
        else throw new IllegalArgumentException("'seed' must be an integer");
        Map<String, Double> params = new LinkedHashMap<>();
        Object po = m.get("params");
        if (po instanceof Map<?, ?> pm) {
            for (var e : pm.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Double d) params.put((String) e.getKey(), d);
                else if (v instanceof Boolean b) params.put((String) e.getKey(), b ? 1.0 : 0.0);
                else throw new IllegalArgumentException("parameter '" + e.getKey() + "' must be a number");
            }
        } else if (po != null) throw new IllegalArgumentException("'params' must be an object");
        Map<String, String> overrides = new LinkedHashMap<>();
        Object ro = m.get("ruleOverrides");
        if (ro instanceof Map<?, ?> rm) {
            for (var e : rm.entrySet()) {
                if (!(e.getValue() instanceof String s)) throw new IllegalArgumentException("rule override '" + e.getKey() + "' must be text");
                if (!s.isBlank()) overrides.put((String) e.getKey(), s);
            }
        }
        Object gv = m.get("generatorVersion");
        String version = gv instanceof String s ? s : Version.ENGINE;
        return new GenerationConfig(ps, seed, params, overrides, version);
    }

    // ---------------------------------------------------------------- presets

    public static String presets() {
        Json.Writer w = new Json.Writer();
        w.object();
        w.field("generatorVersion", Version.ENGINE);
        w.key("presets").array();
        for (Preset p : PresetRegistry.all()) preset(w, p);
        w.end();
        w.end();
        return w.toString();
    }

    public static void preset(Json.Writer w, Preset p) {
        Params def = Params.resolve(p.params(), Map.of());
        w.object();
        w.field("id", p.id());
        w.field("title", p.title());
        w.field("summary", p.summary());
        w.key("notes").array();
        p.notes().forEach(w::value);
        w.end();
        w.key("params").array();
        for (ParamSpec s : p.params()) {
            w.object().field("key", s.key()).field("label", s.label()).field("help", s.help()).field("kind", s.kind())
                .field("min", s.min()).field("max", s.max()).field("step", s.step()).field("default", s.defaultValue())
                .field("group", s.group()).end();
        }
        w.end();
        w.key("programs").array();
        p.programIds().forEach(w::value);
        w.end();
        int[] size = p.gridSize(def);
        w.key("grid").array().value(size[0]).value(size[1]).value(size[2]).end();
        w.key("objectives").array();
        p.refineProfile(def, p.compose(def, new studio.forma.engine.core.Rng(1))).objectives.forEach(o -> w.object().field("key", o.key()).field("label", o.label()).field("description", o.description()).end());
        w.end();
        w.end();
    }

    // ---------------------------------------------------------------- scene

    public static String scene(GenerationResult r, String jobId) {
        Scene s = r.scene();
        Json.Writer w = new Json.Writer(1 << 20);
        w.object();
        w.field("schema", Version.SCENE_SCHEMA);
        w.field("generatorVersion", Version.ENGINE);
        if (jobId != null) w.field("jobId", jobId);
        w.key("config");
        config(w, r.config());
        w.key("resolvedParams").object();
        r.params().asMap().forEach(w::field);
        w.end();
        w.field("presetTitle", r.preset().title());
        w.key("grid").object().field("sx", r.grid().sx).field("sy", r.grid().sy).field("sz", r.grid().sz)
            .field("cellMeters", 1.5).field("storeyCells", 2).field("groundLevel", r.massing().groundLevel()).end();
        w.key("units").value("1 unit = 1 grid cell = 1.5 m; x east, y up, z south; origin at the bottom centre of the grid");
        double[] cam = r.preset().camera(r.params());
        w.key("camera").object().key("target").array().value(cam[0]).value(cam[1]).value(cam[2]).end()
            .field("distance", cam[3]).field("azimuth", cam[4]).field("elevation", cam[5]).end();
        boolean fantasy = r.params().asMap().getOrDefault("fantasy", 0.0) >= 0.5;
        var atm = r.preset().atmosphere(r.params());
        w.key("atmosphere").object().field("cloudLevel", atm.cloudLevel()).field("fantasy", fantasy)
            .field("cloudy", atm.clouds()).field("sky", atm.sky()).field("projection", atm.projection()).end();
        double[] b = s.bounds();
        w.key("bounds").array();
        for (double v : b) w.value(v);
        w.end();
        w.key("components").array();
        for (Components.Component c : r.components().all())
            w.object().field("id", c.id()).field("name", c.name()).field("kind", c.kind())
                .field("material", c.material().name().toLowerCase()).field("floating", c.floating()).end();
        w.end();
        w.key("groups").array();
        for (Scene.Group g : s.groups()) {
            w.object().field("kind", g.kind).field("material", g.material).field("count", g.count())
                .field("data", Json.b64(g.data())).field("comp", Json.b64u16(g.comps())).end();
        }
        w.end();
        w.key("stats").object().field("instances", s.instanceCount()).field("groups", s.groups().size())
            .field("cells", r.grid().size()).field("composeMs", r.composeMillis()).field("realizeMs", r.realizeMillis())
            .field("sceneMs", r.sceneMillis()).field("totalMs", r.totalMillis()).field("ruleSteps", r.ruleSteps())
            .field("historyFrames", r.history().frames().size()).field("historyChanges", r.history().recordedChanges()).end();
        w.end();
        return w.toString();
    }

    // ---------------------------------------------------------------- replay

    public static String replay(GenerationResult r) {
        History h = r.history();
        Json.Writer w = new Json.Writer(1 << 20);
        w.object();
        w.field("schema", "forma-replay/1");
        w.key("grid").object().field("sx", r.grid().sx).field("sy", r.grid().sy).field("sz", r.grid().sz).end();
        w.key("states").array();
        for (int i = 0; i < Cell.COUNT; i++) w.value(Cell.name(i));
        w.end();
        w.key("stages").array();
        for (History.Stage st : h.stages())
            w.object().field("id", st.id()).field("label", st.label()).field("kind", st.kind()).field("description", st.description())
                .field("firstFrame", st.firstFrame()).field("lastFrame", st.lastFrame()).end();
        w.end();
        w.key("frames").array();
        for (History.Frame f : h.frames())
            w.object().field("stage", f.stage()).field("label", f.label()).field("n", f.size())
                .field("idx", Json.b64i32(f.indices())).field("val", Json.b64(f.states())).end();
        w.end();
        w.field("totalChanges", h.totalChanges());
        w.field("recordedChanges", h.recordedChanges());
        w.end();
        return w.toString();
    }

    // ---------------------------------------------------------------- inspector

    public static String inspector(GenerationResult r) {
        Json.Writer w = new Json.Writer(1 << 16);
        w.object();
        w.key("stages").array();
        for (History.Stage st : r.history().stages()) {
            GenContext.StageNotes n = r.stageNotes().get(st.id());
            w.object().field("id", st.id()).field("label", st.label()).field("kind", st.kind()).field("description", st.description())
                .field("ms", r.stageMillis().getOrDefault(st.id(), 0L)).field("firstFrame", st.firstFrame()).field("lastFrame", st.lastFrame());
            w.key("operations").array();
            if (n != null) n.operations.forEach(w::value);
            w.end();
            w.key("programs").array();
            if (n != null) n.programs.forEach((id, src) -> w.object().field("id", id).field("source", src)
                .field("overridden", n.programOverridden.getOrDefault(id, false)).end());
            w.end();
            w.end();
        }
        w.end();
        w.key("rules").array();
        for (RuleLog.RuleRecord rr : r.ruleLog().rules()) {
            w.object().field("stage", rr.stage).field("node", rr.node).field("nodeType", rr.nodeType).field("rule", rr.ruleId)
                .field("description", rr.description).field("input", rr.input).field("output", rr.output)
                .field("symmetry", rr.symmetry).field("p", rr.p).field("variants", rr.variants)
                .field("applications", rr.applications).field("firstStep", rr.firstStep).field("lastStep", rr.lastStep);
            if (rr.sample != null) {
                RuleLog.Sample sm = rr.sample;
                w.key("sample").object().field("x", sm.x()).field("y", sm.y()).field("z", sm.z())
                    .field("nx", sm.nx()).field("ny", sm.ny()).field("nz", sm.nz()).field("variant", sm.variant())
                    .field("before", Json.b64(sm.before())).field("after", Json.b64(sm.after())).end();
            }
            w.end();
        }
        w.end();
        w.key("nodes").array();
        for (RuleLog.NodeRecord nr : r.ruleLog().nodes())
            w.object().field("stage", nr.stage()).field("node", nr.node()).field("type", nr.type()).field("steps", nr.steps())
                .field("exhausted", nr.exhausted()).end();
        w.end();
        w.key("checks");
        checks(w, r.report());
        w.key("massing");
        massing(w, r.massing());
        w.key("graph");
        graph(w, r.roomGraph());
        w.key("states").array();
        for (int i = 0; i < Cell.COUNT; i++) w.object().field("name", Cell.name(i)).field("symbol", String.valueOf(Cell.symbol(i))).end();
        w.end();
        w.key("legendUnions").object();
        studio.forma.engine.rules.Legend.standard().unions().forEach((ch, mask) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Cell.COUNT; i++) if ((mask & (1 << i)) != 0) sb.append(sb.length() == 0 ? "" : ", ").append(Cell.name(i));
            w.field(String.valueOf(ch), sb.toString());
        });
        w.end();
        int[] hist = r.grid().histogram();
        w.key("histogram").object();
        for (int i = 0; i < hist.length; i++) if (hist[i] > 0) w.field(Cell.name(i), hist[i]);
        w.end();
        w.end();
        return w.toString();
    }

    public static void checks(Json.Writer w, ConstraintReport rep) {
        w.array();
        for (ConstraintReport.Check c : rep.checks())
            w.object().field("id", c.id()).field("label", c.label()).field("kind", c.kind())
                .field("status", c.status().name().toLowerCase()).field("detail", c.detail()).field("value", c.value()).end();
        w.end();
    }

    public static void massing(Json.Writer w, Massing m) {
        w.object();
        w.field("groundLevel", m.groundLevel());
        w.key("volumes").array();
        for (Massing.Volume v : m.volumes())
            w.object().field("name", v.name()).field("role", v.role()).field("shape", v.shape().name().toLowerCase())
                .field("cx", v.cx() - m.sx() / 2.0).field("cz", v.cz() - m.sz() / 2.0).field("w", v.w()).field("d", v.d())
                .field("y0", v.y0()).field("storeys", v.storeys()).field("terraced", v.terraced()).field("courtyard", v.courtyard())
                .field("floating", v.floating()).end();
        w.end();
        w.key("links").array();
        for (Massing.Link l : m.links())
            w.object().field("a", l.a()).field("b", l.b()).field("level", l.level()).field("kind", l.kind()).field("enabled", l.enabled()).end();
        w.end();
        w.key("voids").array();
        for (Massing.Void v : m.voids())
            w.object().field("name", v.name()).field("cx", v.cx() - m.sx() / 2.0).field("cz", v.cz() - m.sz() / 2.0)
                .field("radius", v.radius()).end();
        w.end();
        w.end();
    }

    public static void graph(Json.Writer w, RoomGraph g) {
        w.object();
        w.key("nodes").array();
        for (RoomGraph.Node n : g.nodes)
            w.object().field("id", n.id()).field("name", n.name()).field("kind", n.kind()).field("cells", n.cells())
                .field("reachable", n.reachable()).field("x", n.x()).field("y", n.y()).field("z", n.z()).end();
        w.end();
        w.key("edges").array();
        for (RoomGraph.Edge e : g.edges)
            w.object().field("a", e.a()).field("b", e.b()).field("via", e.via()).field("crossings", e.crossings()).end();
        w.end();
        w.end();
    }

    public static void energy(Json.Writer w, MetropolisSampler.Breakdown b) {
        w.object().field("total", b.total());
        w.key("components").object();
        b.components().forEach(w::field);
        w.end();
        w.end();
    }

    public static <S> void refinement(Json.Writer w, MetropolisSampler.Result<S> res, Map<String, Double> weights,
                                      double t0, double t1, int requested, long millis, List<Map.Entry<String, String>> objectiveLabels) {
        w.object();
        w.field("mode", res.mode());
        w.field("temperature", t0);
        w.field("temperatureEnd", t1);
        w.field("iterationsRequested", requested);
        w.field("iterations", res.iterations());
        w.field("accepted", res.accepted());
        w.field("acceptedUphill", res.acceptedUphill());
        w.field("rejectedByConstraint", res.rejectedByConstraint());
        w.field("acceptanceRate", res.iterations() == 0 ? 0 : (double) res.accepted() / res.iterations());
        w.field("cancelled", res.cancelled());
        w.field("millis", millis);
        w.key("constraintRejections").object();
        res.constraintRejections().forEach((k, v) -> w.field(k, v));
        w.end();
        w.key("weights").object();
        weights.forEach(w::field);
        w.end();
        w.key("objectives").array();
        for (var e : objectiveLabels) w.object().field("key", e.getKey()).field("label", e.getValue()).end();
        w.end();
        w.key("initial");
        energy(w, res.initialEnergy());
        w.key("final");
        energy(w, res.finalEnergy());
        w.key("best");
        energy(w, res.bestEnergy());
        w.key("trace").array();
        for (double v : res.trace()) w.value(v);
        w.end();
        w.key("recent").array();
        for (MetropolisSampler.Step st : res.recentAccepted())
            w.object().field("iteration", st.iteration()).field("move", st.move()).field("deltaE", st.deltaE())
                .field("p", st.acceptProbability()).field("uphill", st.uphill()).field("energy", st.energyAfter()).end();
        w.end();
        w.end();
    }
}
