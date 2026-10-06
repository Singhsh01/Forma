package studio.forma.engine;

import org.junit.jupiter.api.Test;
import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Rng;
import studio.forma.engine.geometry.Scene;
import studio.forma.engine.io.Json;
import studio.forma.engine.io.Serializers;
import studio.forma.engine.mcmc.MassingRefiner;
import studio.forma.engine.mcmc.MetropolisSampler;
import studio.forma.engine.mcmc.RefineProfile;
import studio.forma.engine.presets.ParamSpec;
import studio.forma.engine.presets.Params;
import studio.forma.engine.presets.Preset;
import studio.forma.engine.presets.PresetRegistry;
import studio.forma.engine.rules.RuleValidationException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {

    static GenerationResult gen(String preset, long seed) {
        return Generator.generate(GenerationConfig.of(preset, seed), Cancellation.none(), null);
    }

    @Test
    void sameConfigReproducesTheSameDesign() {
        for (Preset p : PresetRegistry.all()) {
            GenerationResult a = gen(p.id(), 11), b = gen(p.id(), 11);
            assertEquals(a.grid().fingerprint(), b.grid().fingerprint(), p.id());
            assertEquals(Serializers.scene(a, null).replaceAll("\"(composeMs|realizeMs|sceneMs|totalMs)\":\\d+", ""),
                Serializers.scene(b, null).replaceAll("\"(composeMs|realizeMs|sceneMs|totalMs)\":\\d+", ""), p.id());
            assertNotEquals(a.grid().fingerprint(), gen(p.id(), 12).grid().fingerprint(), p.id() + ": a different seed changes the design");
        }
    }

    @Test
    void everyPresetPassesItsHardChecks() {
        for (Preset p : PresetRegistry.all())
            for (long seed : new long[]{1, 7, 42}) {
                GenerationResult r = gen(p.id(), seed);
                for (ConstraintReport.Check c : r.report().checks())
                    if (c.kind().equals("hard")) assertNotEquals(ConstraintReport.Status.FAIL, c.status(), p.id() + " seed " + seed + ": " + c.label() + " - " + c.detail());
                assertTrue(r.scene().instanceCount() > 250, p.id() + " produced too little geometry");
            }
    }

    @Test
    void parametersChangeTheGeometry() {
        for (Preset p : PresetRegistry.all()) {
            GenerationResult base = gen(p.id(), 5);
            for (ParamSpec s : p.params()) {
                double alt = s.kind().equals("bool") ? 1 - s.defaultValue() : (s.defaultValue() == s.max() ? s.min() : s.max());
                GenerationConfig cfg = GenerationConfig.of(p.id(), 5).withParam(s.key(), alt);
                GenerationResult r = Generator.generate(cfg, Cancellation.none(), null);
                assertNotEquals(base.grid().fingerprint(), r.grid().fingerprint(), p.id() + ": parameter '" + s.key() + "' has no effect");
            }
        }
    }

    /** Composition must always produce a massing inside the valid state space of its refinement. */
    @Test
    void composedMassingsSatisfyHardConstraints() {
        Rng r = new Rng(99);
        for (Preset p : PresetRegistry.all())
            for (int k = 0; k < 25; k++) {
                Map<String, Double> in = new LinkedHashMap<>();
                for (ParamSpec s : p.params()) in.put(s.key(), s.min() + r.nextDouble() * (s.max() - s.min()));
                Params params = Params.resolve(p.params(), in);
                Massing m = p.compose(params, new Rng(r.nextLong()));
                RefineProfile prof = p.refineProfile(params, m);
                MassingRefiner refiner = new MassingRefiner(prof, Map.of());
                for (var c : refiner.constraints()) assertNull(c.violation(m), p.id() + " " + params.asMap());
            }
    }

    @Test
    void refinementRunsAndReRealises() {
        GenerationResult base = gen("library", 3);
        RefineProfile prof = base.preset().refineProfile(base.params(), base.massing());
        MassingRefiner ref = new MassingRefiner(prof, Map.of());
        var res = ref.run(base.massing(), 600, MetropolisSampler.Schedule.fixed(0.3), new Rng(4), Cancellation.none(), null);
        assertEquals(600, res.iterations());
        assertTrue(res.accepted() > 0);
        assertTrue(res.bestEnergy().total() <= res.initialEnergy().total());
        GenerationResult after = Generator.realize(base.config(), base.preset(), base.params(), res.finalState(), Cancellation.none(), null, 0);
        for (ConstraintReport.Check c : after.report().checks())
            if (c.kind().equals("hard")) assertNotEquals(ConstraintReport.Status.FAIL, c.status(), c.label() + ": " + c.detail());
        // reproducible: same seed, same chain
        var res2 = ref.run(base.massing(), 600, MetropolisSampler.Schedule.fixed(0.3), new Rng(4), Cancellation.none(), null);
        assertEquals(res.finalEnergy().total(), res2.finalEnergy().total());
    }

    @Test
    void cancellationAbortsGeneration() {
        Cancellation c = new Cancellation();
        c.cancel();
        assertThrows(Cancellation.CancelledException.class, () -> Generator.generate(GenerationConfig.of("library", 1), c, null));
    }

    @Test
    void replayReproducesTheFinalGrid() {
        GenerationResult r = gen("library", 8);
        byte[] replayed = r.history().replay(r.history().frames().size() - 1);
        assertArrayEquals(r.grid().rawStates(), replayed);
        assertTrue(r.history().frames().size() <= 720);
        assertFalse(r.history().stages().isEmpty());
    }

    @Test
    void configRoundTripsThroughJson() {
        GenerationConfig c = new GenerationConfig("library", 123456789L, Map.of("height", 14.0, "fantasy", 1.0),
            Map.of("refine", "sequence s\n  prl a steps=1\n    rule r \"T\" -> \"G\"\n"), Version.ENGINE);
        Json.Writer w = new Json.Writer();
        Serializers.config(w, c);
        GenerationConfig back = Serializers.parseConfig(Json.parseObject(w.toString()));
        assertEquals(c, back);
    }

    @Test
    void ruleOverridesAreValidatedAndApplied() {
        GenerationConfig bad = new GenerationConfig("library", 1, Map.of(), Map.of("growth", "prl x\n  rule r \"T\" -> \"?\"\n"), null);
        var e = assertThrows(RuleValidationException.class, () -> Generator.generate(bad, Cancellation.none(), null));
        assertTrue(e.getMessage().contains("program 'growth'"), e.getMessage());
        var unknown = new GenerationConfig("library", 1, Map.of(), Map.of("nope", "prl x\n  rule r \"T\" -> \"G\"\n"), null);
        assertThrows(RuleValidationException.class, () -> Generator.generate(unknown, Cancellation.none(), null));
        // a custom refine program that plants every terrace changes the result
        String plantAll = "sequence refine\n  prl plant steps=1\n    rule g \"T E\" -> \"G E\" sym=none\n";
        GenerationResult r = Generator.generate(new GenerationConfig("library", 1, Map.of(), Map.of("refine", plantAll), null), Cancellation.none(), null);
        assertTrue(r.ruleLog().applicationsOf("g") > 100);
    }

    @Test
    void sceneGeometryIsWellFormed() {
        GenerationResult r = gen("library", 2);
        Scene s = r.scene();
        double[] b = s.bounds();
        for (Scene.Group g : s.groups()) {
            float[] d = g.data();
            int[] comps = g.comps();
            assertEquals(g.count() * Scene.STRIDE, d.length);
            assertEquals(g.count(), comps.length);
            for (int i = 0; i < g.count(); i++) {
                for (int k = 0; k < 6; k++) assertTrue(Float.isFinite(d[i * Scene.STRIDE + k]));
                for (int k = 3; k < 6; k++) assertTrue(d[i * Scene.STRIDE + k] > 0, "positive size in " + g.kind + "/" + g.material);
                assertTrue(comps[i] >= 0 && comps[i] < r.components().size());
                assertTrue(d[i * Scene.STRIDE] >= b[0] - 1e-3 && d[i * Scene.STRIDE] <= b[3] + 1e-3);
            }
        }
        Map<String, Object> parsed = Json.parseObject(Serializers.scene(r, "job-1"));
        assertEquals(Version.SCENE_SCHEMA, parsed.get("schema"));
        List<?> groups = (List<?>) parsed.get("groups");
        assertEquals(s.groups().size(), groups.size());
        int total = 0;
        for (Object o : groups) total += ((Double) ((Map<?, ?>) o).get("count")).intValue();
        assertEquals(s.instanceCount(), total);
        assertEquals(Arrays.stream(new int[]{r.grid().sx}).sum(), ((Double) ((Map<?, ?>) parsed.get("grid")).get("sx")).intValue());
    }

    @Test
    void jsonParserIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1 2]"));
        assertEquals(Map.of("a", List.of(1.0, "x\n", true)), Json.parse("{\"a\":[1,\"x\\n\",true]}"));
    }
}
