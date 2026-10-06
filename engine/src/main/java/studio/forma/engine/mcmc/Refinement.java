package studio.forma.engine.mcmc;

import studio.forma.engine.GenContext;
import studio.forma.engine.GenerationResult;
import studio.forma.engine.Generator;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Rng;
import studio.forma.engine.io.Json;
import studio.forma.engine.io.Serializers;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs MCMC refinement on a generated design and re-realises the result. Shared by the CLI and server. */
public final class Refinement {
    private Refinement() {}

    public record Settings(int iterations, double temperature, double temperatureEnd, boolean anneal,
                           Map<String, Double> weights, long mcmcSeed, boolean keepBest) {
        public MetropolisSampler.Schedule schedule() {
            return anneal ? MetropolisSampler.Schedule.annealing(temperature, temperatureEnd) : MetropolisSampler.Schedule.fixed(temperature);
        }
    }

    public record Outcome(GenerationResult before, GenerationResult after, MetropolisSampler.Result<Massing> result,
                          Settings settings, long mcmcMillis, RefineProfile profile) {}

    public interface Progress {
        void progress(String stage, double fraction, String message);
    }

    public static Outcome run(GenerationResult before, Settings s, Cancellation cancel, Progress progress) {
        RefineProfile profile = before.preset().refineProfile(before.params(), before.massing());
        MassingRefiner refiner = new MassingRefiner(profile, s.weights());
        MetropolisSampler.Schedule sched = s.schedule();
        long t0 = System.currentTimeMillis();
        // the chain's randomness depends on the design seed and the sampler seed only
        Rng rng = new Rng(before.config().seed()).fork("mcmc:" + s.mcmcSeed());
        MetropolisSampler.Result<Massing> res = refiner.run(before.massing(), s.iterations(), sched, rng, cancel,
            (k, n, e, acc) -> {
                if (progress != null) progress.progress("mcmc", 0.05 + 0.6 * k / Math.max(1, n),
                    String.format("%s step %d of %d, energy %.3f, %d accepted", sched.mode(), k, n, e, acc));
            });
        long ms = System.currentTimeMillis() - t0;
        if (cancel != null && cancel.isCancelled()) throw new Cancellation.CancelledException("refinement cancelled");
        if (progress != null) progress.progress("realize", 0.7, "realising the refined massing");
        GenContext.ProgressListener pl = progress == null ? null
            : (id, label, f, msg) -> progress.progress("realize:" + id, 0.7 + 0.28 * f, "refined: " + label);
        GenerationResult after = Generator.realize(before.config(), before.preset(), before.params(), s.keepBest() ? res.bestState() : res.finalState(), cancel, pl, 0);
        return new Outcome(before, after, res, s, ms, profile);
    }

    public static String json(Outcome o, String baseJobId) {
        List<Map.Entry<String, String>> labels = new ArrayList<>();
        o.profile().objectives.forEach(x -> labels.add(new AbstractMap.SimpleEntry<>(x.key(), x.label())));
        Map<String, Double> w = new LinkedHashMap<>();
        o.profile().objectives.forEach(x -> w.put(x.key(), o.settings().weights().getOrDefault(x.key(), 1.0)));
        Settings s = o.settings();
        Json.Writer jw = new Json.Writer(8192);
        jw.object();
        jw.field("baseJobId", baseJobId);
        jw.field("mcmcSeed", s.mcmcSeed());
        jw.key("result");
        Serializers.refinement(jw, o.result(), w, s.temperature(), s.anneal() ? s.temperatureEnd() : s.temperature(),
            s.iterations(), o.mcmcMillis(), labels);
        // which state of the chain was realised as the refined design
        jw.field("kept", s.keepBest() ? "best" : "final");
        jw.key("before");
        Serializers.checks(jw, o.before().report());
        jw.key("after");
        Serializers.checks(jw, o.after().report());
        jw.key("massingBefore");
        Serializers.massing(jw, o.before().massing());
        jw.key("massingAfter");
        Serializers.massing(jw, o.after().massing());
        jw.key("target").value(s.anneal()
            ? "Simulated annealing: the temperature falls from T0 to T1, so the chain drifts toward low-energy massings. It is an optimiser, not a sampler of one fixed distribution."
            : "MCMC at fixed temperature T samples massings with probability proportional to exp(-E/T) among states that satisfy every hard constraint.");
        jw.end();
        return jw.toString();
    }
}
