package studio.forma.server;

import studio.forma.engine.GenerationConfig;
import studio.forma.engine.GenerationResult;
import studio.forma.engine.Generator;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.mcmc.Refinement;
import studio.forma.engine.presets.PresetRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded job execution: a fixed pool of workers, a short queue, a cap on retained jobs and a
 * per-job time limit. Jobs never share mutable state, so results are deterministic regardless of
 * which worker runs them.
 */
public final class JobManager {
    public static final int MAX_ITERATIONS = 20_000;

    public record RefineSpec(int iterations, double temperature, double temperatureEnd, boolean anneal,
                             Map<String, Double> weights, long mcmcSeed, boolean keepBest) {}

    public static final class Rejected extends RuntimeException {
        public final int status;

        Rejected(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    private final ThreadPoolExecutor pool;
    private final Map<String, Job> jobs;
    private final int retain;
    private final long timeoutMs;
    private final AtomicLong counter = new AtomicLong();

    public JobManager(int workers, int queue, int retain, long timeoutMs) {
        this.pool = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, "forma-worker");
            t.setDaemon(true);
            return t;
        });
        this.retain = retain;
        this.timeoutMs = timeoutMs;
        this.jobs = new LinkedHashMap<>();
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized int activeCount() {
        int n = 0;
        for (Job j : jobs.values()) if (!j.isTerminal()) n++;
        return n;
    }

    private synchronized void put(Job j) {
        jobs.put(j.id, j);
        // evict the oldest finished jobs beyond the retention limit (bounded memory)
        if (jobs.size() > retain) {
            List<String> drop = new ArrayList<>();
            int excess = jobs.size() - retain;
            for (Job o : jobs.values()) {
                if (excess <= 0) break;
                if (o.isTerminal()) {
                    drop.add(o.id);
                    excess--;
                }
            }
            drop.forEach(jobs::remove);
        }
    }

    private String newId(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36) + "-" + counter.incrementAndGet();
    }

    public Job submitGenerate(GenerationConfig cfg) {
        validate(cfg);
        Job job = new Job(newId("gen"), "generate", cfg, timeoutMs, null);
        submit(job, () -> {
            GenerationResult r = Generator.generate(cfg, job.cancellation,
                (id, label, f, msg) -> job.progress(id, f, label + (msg.endsWith("done") ? " ✓" : "")));
            job.complete(r, null);
        });
        return job;
    }

    /** Rejects unknown presets, unknown parameter names and invalid rule overrides before queueing. */
    static void validate(GenerationConfig cfg) {
        var preset = PresetRegistry.get(cfg.preset());
        var known = new java.util.HashSet<String>();
        preset.params().forEach(p -> known.add(p.key()));
        for (String k : cfg.params().keySet())
            if (!known.contains(k)) throw new IllegalArgumentException("unknown parameter '" + k + "' for " + cfg.preset() + " (known: " + String.join(", ", known) + ")");
        Generator.validateOverrides(preset, cfg.ruleOverrides());
    }

    public Job submitRefine(GenerationConfig cfg, String baseJobId, RefineSpec spec) {
        if (spec.iterations() < 1 || spec.iterations() > MAX_ITERATIONS)
            throw new IllegalArgumentException("iterations must be between 1 and " + MAX_ITERATIONS);
        if (!(spec.temperature() > 0) || spec.temperature() > 100) throw new IllegalArgumentException("temperature must be in (0, 100]");
        if (spec.anneal() && (!(spec.temperatureEnd() > 0) || spec.temperatureEnd() > spec.temperature()))
            throw new IllegalArgumentException("annealing needs 0 < final temperature <= initial temperature");
        Job base = baseJobId == null ? null : get(baseJobId);
        GenerationConfig baseCfg = base != null && base.status() == Job.Status.COMPLETED ? base.config : cfg;
        if (baseCfg == null) throw new IllegalArgumentException("refinement needs a completed baseJobId or a config");
        if (base == null) validate(baseCfg);
        Job job = new Job(newId("ref"), "refine", baseCfg, timeoutMs, base != null ? base.id : null);
        submit(job, () -> {
            GenerationResult before = base != null && base.status() == Job.Status.COMPLETED ? base.result() : null;
            if (before == null) {
                job.progress("base", 0.02, "regenerating the base design");
                before = Generator.generate(baseCfg, job.cancellation, null);
            }
            Refinement.Settings settings = new Refinement.Settings(spec.iterations(), spec.temperature(), spec.temperatureEnd(),
                spec.anneal(), spec.weights(), spec.mcmcSeed(), spec.keepBest());
            Refinement.Outcome o = Refinement.run(before, settings, job.cancellation, job::progress);
            job.complete(o.after(), Refinement.json(o, base != null ? base.id : null));
        });
        return job;
    }

    private void submit(Job job, Runnable body) {
        synchronized (this) {
            if (activeCount() >= pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity() + pool.getQueue().size())
                throw new Rejected(429, "the generator is busy: too many jobs are running or queued; try again shortly");
        }
        put(job);
        try {
            pool.execute(() -> {
                if (job.isTerminal()) return;
                if (job.cancellation.isCancelled()) {
                    job.cancelled("cancelled before it started");
                    return;
                }
                job.start();
                try {
                    body.run();
                } catch (Cancellation.CancelledException e) {
                    job.cancelled(e.getMessage());
                } catch (OutOfMemoryError e) {
                    job.fail("ran out of memory; try smaller parameters");
                } catch (RuntimeException e) {
                    job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (this) {
                jobs.remove(job.id);
            }
            throw new Rejected(429, "the generator queue is full; try again shortly");
        }
    }

    public boolean cancel(String id) {
        Job j = get(id);
        if (j == null) return false;
        j.cancellation.cancel();
        if (j.status() == Job.Status.QUEUED) j.cancelled("cancelled before it started");
        return true;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
