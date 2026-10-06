package studio.forma.engine.mcmc;

import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Metropolis-Hastings sampler over an arbitrary design state.
 *
 * <p>At a fixed temperature {@code T} the chain targets
 * {@code pi(x) proportional to exp(-E(x) / T)} restricted to states that satisfy every hard
 * constraint. A proposal x -> x' is accepted with probability
 * {@code min(1, exp(-(E(x') - E(x)) / T) * q(x | x') / q(x' | x))}; kernels report the log
 * proposal densities, and symmetric kernels report equal values so the ratio cancels.
 * Proposals violating a hard constraint have target probability zero and are rejected (the
 * chain stays put, which keeps detailed balance on the valid set).
 *
 * <p>When the temperature changes over time ({@link Schedule#annealing}) the chain is no longer
 * a stationary sampler of one distribution; that mode is simulated annealing and is labelled so.
 */
public final class MetropolisSampler<S> {

    public interface Kernel<S> {
        Proposal<S> propose(S current, Rng rng);
    }

    /** @param logForward log q(x'|x); @param logReverse log q(x|x') */
    public record Proposal<S>(S candidate, String move, double logForward, double logReverse) {
        public static <S> Proposal<S> symmetric(S candidate, String move) {
            return new Proposal<>(candidate, move, 0, 0);
        }
    }

    public interface Energy<S> {
        Breakdown evaluate(S state);
    }

    public interface HardConstraint<S> {
        /** @return null when satisfied, otherwise a short reason */
        String violation(S state);
    }

    /** Total energy with named weighted components (each already multiplied by its weight). */
    public record Breakdown(double total, Map<String, Double> components) {}

    public record Schedule(double t0, double t1, boolean annealing) {
        public static Schedule fixed(double t) {
            if (!(t > 0)) throw new IllegalArgumentException("temperature must be > 0");
            return new Schedule(t, t, false);
        }

        public static Schedule annealing(double t0, double t1) {
            if (!(t0 > 0 && t1 > 0)) throw new IllegalArgumentException("temperatures must be > 0");
            return new Schedule(t0, t1, true);
        }

        public double at(int k, int n) {
            if (!annealing || n <= 1) return t0;
            return t0 * Math.pow(t1 / t0, (double) k / (n - 1));
        }

        public String mode() {
            return annealing ? "simulated-annealing" : "mcmc";
        }
    }

    public record Step(int iteration, String move, double deltaE, double acceptProbability, boolean uphill, double energyAfter) {}

    public record Result<S>(S finalState, Breakdown finalEnergy, S bestState, Breakdown bestEnergy,
                            Breakdown initialEnergy, int iterations, int accepted, int acceptedUphill,
                            int rejectedByConstraint, Map<String, Integer> constraintRejections,
                            double[] trace, List<Step> recentAccepted, String mode, boolean cancelled) {}

    public interface Listener<S> {
        void progress(int iteration, int total, double energy, int accepted);
    }

    private final Kernel<S> kernel;
    private final Energy<S> energy;
    private final List<HardConstraint<S>> constraints;

    public MetropolisSampler(Kernel<S> kernel, Energy<S> energy, List<HardConstraint<S>> constraints) {
        this.kernel = kernel;
        this.energy = energy;
        this.constraints = List.copyOf(constraints);
    }

    public String violation(S s) {
        for (HardConstraint<S> c : constraints) {
            String v = c.violation(s);
            if (v != null) return v;
        }
        return null;
    }

    public Result<S> run(S initial, int iterations, Schedule schedule, Rng rng, Cancellation cancel,
                         Listener<S> listener) {
        if (iterations < 0 || iterations > 2_000_000) throw new IllegalArgumentException("iterations out of range");
        String v0 = violation(initial);
        if (v0 != null) throw new IllegalArgumentException("initial state violates a hard constraint: " + v0);
        S cur = initial;
        Breakdown curE = energy.evaluate(cur);
        Breakdown initE = curE;
        S best = cur;
        Breakdown bestE = curE;
        int accepted = 0, uphill = 0, rejectedC = 0;
        Map<String, Integer> reasons = new java.util.TreeMap<>();
        int traceLen = Math.min(240, Math.max(1, iterations));
        double[] trace = new double[traceLen];
        java.util.ArrayDeque<Step> recent = new java.util.ArrayDeque<>();
        boolean cancelled = false;
        int k = 0;
        for (; k < iterations; k++) {
            if (cancel != null && cancel.isCancelled()) {
                cancelled = true;
                break;
            }
            double t = schedule.at(k, iterations);
            Proposal<S> prop = kernel.propose(cur, rng);
            if (prop != null && prop.candidate() != null) {
                String viol = violation(prop.candidate());
                if (viol != null) {
                    rejectedC++;
                    reasons.merge(viol, 1, Integer::sum);
                } else {
                    Breakdown e = energy.evaluate(prop.candidate());
                    double dE = e.total() - curE.total();
                    double logA = -dE / t + (prop.logReverse() - prop.logForward());
                    double a = logA >= 0 ? 1.0 : Math.exp(logA);
                    if (rng.nextDouble() < a) {
                        cur = prop.candidate();
                        curE = e;
                        accepted++;
                        boolean up = dE > 0;
                        if (up) uphill++;
                        recent.addLast(new Step(k, prop.move(), dE, a, up, e.total()));
                        if (recent.size() > 40) recent.removeFirst();
                        if (e.total() < bestE.total()) {
                            best = cur;
                            bestE = e;
                        }
                    }
                }
            } else {
                rejectedC++;
                reasons.merge("no valid move", 1, Integer::sum);
            }
            int slot = (int) ((long) k * traceLen / Math.max(1, iterations));
            trace[slot] = curE.total();
            if (listener != null && (k % 50 == 0)) listener.progress(k, iterations, curE.total(), accepted);
        }
        if (k < iterations && k > 0) {
            int filled = (int) ((long) (k - 1) * traceLen / iterations);
            double[] t2 = new double[filled + 1];
            System.arraycopy(trace, 0, t2, 0, filled + 1);
            trace = t2;
        }
        return new Result<>(cur, curE, best, bestE, initE, k, accepted, uphill, rejectedC, reasons, trace,
            new ArrayList<>(recent), schedule.mode(), cancelled);
    }
}
