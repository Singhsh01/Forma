package studio.forma.engine.cli;

import studio.forma.engine.GenerationConfig;
import studio.forma.engine.GenerationResult;
import studio.forma.engine.Generator;
import studio.forma.engine.arch.ConstraintReport;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.io.Json;
import studio.forma.engine.io.Serializers;
import studio.forma.engine.presets.PresetRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command-line generator.
 *
 * <pre>
 *   forma list
 *   forma generate &lt;preset&gt; [--seed N] [--param key=value ...] [--out dir]
 *   forma config &lt;config.json&gt; [--out dir]
 * </pre>
 * Writes {@code scene.json}, {@code replay.json}, {@code inspector.json} and {@code config.json}.
 */
public final class FormaCli {
    public static void main(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println("usage: forma list | generate <preset> [--seed N] [--param k=v ...] [--out dir] | config <file> [--out dir] | refine <preset> [--seed N] [--iterations N] [--temperature T] [--anneal T1] [--mcmc-seed S] [--keep best|final] [--out dir]");
            return;
        }
        switch (args[0]) {
            case "list" -> PresetRegistry.all().forEach(p -> System.out.println(p.id() + "\t" + p.title()));
            case "generate", "config" -> {
                GenerationConfig cfg;
                Path out = Path.of("forma-out");
                int i = 2;
                if (args[0].equals("config")) {
                    cfg = Serializers.parseConfig(Json.parseObject(Files.readString(Path.of(args[1]))));
                } else {
                    long seed = 1;
                    Map<String, Double> params = new LinkedHashMap<>();
                    for (int k = 2; k < args.length; k++) {
                        if (args[k].equals("--seed")) seed = Long.parseLong(args[++k]);
                        else if (args[k].equals("--param")) {
                            String[] kv = args[++k].split("=", 2);
                            params.put(kv[0], Double.parseDouble(kv[1]));
                        }
                    }
                    cfg = new GenerationConfig(args[1], seed, params, Map.of(), null);
                }
                for (int k = i; k < args.length; k++) if (args[k].equals("--out")) out = Path.of(args[k + 1]);
                GenerationResult r = Generator.generate(cfg, new Cancellation(120_000),
                    (id, label, f, msg) -> System.err.printf("  [%3.0f%%] %s%n", f * 100, msg));
                Files.createDirectories(out);
                String scene = Serializers.scene(r, null);
                String replay = Serializers.replay(r);
                Files.writeString(out.resolve("scene.json"), scene, StandardCharsets.UTF_8);
                Files.writeString(out.resolve("replay.json"), replay, StandardCharsets.UTF_8);
                Files.writeString(out.resolve("inspector.json"), Serializers.inspector(r), StandardCharsets.UTF_8);
                Json.Writer cw = new Json.Writer();
                Serializers.config(cw, cfg);
                Files.writeString(out.resolve("config.json"), cw.toString(), StandardCharsets.UTF_8);
                System.out.printf("%s seed %d: compose %d ms, realize %d ms, geometry %d ms, %d instances, %d rule steps%n",
                    cfg.preset(), cfg.seed(), r.composeMillis(), r.realizeMillis(), r.sceneMillis(), r.scene().instanceCount(), r.ruleSteps());
                System.out.printf("scene.json %,d bytes, replay.json %,d bytes (%d frames, %,d changes)%n",
                    scene.length(), replay.length(), r.history().frames().size(), r.history().recordedChanges());
                for (ConstraintReport.Check c : r.report().checks())
                    System.out.printf("  %-5s %-48s %s%n", c.status(), c.label(), c.detail());
            }
            case "refine" -> {
                // forma refine <preset> [--seed N] [--iterations N] [--temperature T] [--anneal T1] [--mcmc-seed S] [--out dir]
                long seed = 1, mseed = 1;
                int iters = 2000;
                double t = 0.25, t1 = 0.02;
                boolean anneal = false, keepBest = true;
                Path out = Path.of("forma-out");
                for (int k = 2; k < args.length; k++) {
                    switch (args[k]) {
                        case "--seed" -> seed = Long.parseLong(args[++k]);
                        case "--iterations" -> iters = Integer.parseInt(args[++k]);
                        case "--temperature" -> t = Double.parseDouble(args[++k]);
                        case "--anneal" -> { anneal = true; t1 = Double.parseDouble(args[++k]); }
                        case "--mcmc-seed" -> mseed = Long.parseLong(args[++k]);
                        case "--keep" -> keepBest = !"final".equals(args[++k]);
                        case "--out" -> out = Path.of(args[++k]);
                        default -> {}
                    }
                }
                GenerationConfig cfg = new GenerationConfig(args[1], seed, Map.of(), Map.of(), null);
                GenerationResult base = Generator.generate(cfg, new Cancellation(120_000), null);
                var o = studio.forma.engine.mcmc.Refinement.run(base,
                    new studio.forma.engine.mcmc.Refinement.Settings(iters, t, t1, anneal, Map.of(), mseed, keepBest), new Cancellation(300_000), null);
                Files.createDirectories(out);
                Files.writeString(out.resolve("refinement.json"), studio.forma.engine.mcmc.Refinement.json(o, null), StandardCharsets.UTF_8);
                Files.writeString(out.resolve("refined-scene.json"), Serializers.scene(o.after(), null), StandardCharsets.UTF_8);
                var r = o.result();
                System.out.printf("%s seed %d: %s, %d iterations in %d ms, accepted %d (%d uphill), energy %.3f -> %.3f (best %.3f)%n",
                    args[1], seed, r.mode(), r.iterations(), o.mcmcMillis(), r.accepted(), r.acceptedUphill(),
                    r.initialEnergy().total(), r.finalEnergy().total(), r.bestEnergy().total());
            }
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }
}
