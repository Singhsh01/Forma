package studio.forma.engine.core;

import java.util.SplittableRandom;

/**
 * Deterministic random source. All randomness in FORMA flows from one seed through
 * named sub-streams ({@link #fork(String)}), so changing how often one stage draws numbers
 * does not shift the numbers seen by unrelated stages. Never shared across threads.
 */
public final class Rng {
    private final long seed;
    private final SplittableRandom r;

    public Rng(long seed) {
        this.seed = seed;
        this.r = new SplittableRandom(mix(seed));
    }

    public long seed() {
        return seed;
    }

    /** An independent stream derived from this stream's seed and a label. */
    public Rng fork(String label) {
        long h = seed;
        for (int i = 0; i < label.length(); i++) h = h * 1099511628211L ^ label.charAt(i);
        return new Rng(mix(h ^ 0x9E3779B97F4A7C15L));
    }

    public int nextInt(int bound) {
        return r.nextInt(bound);
    }

    /** Inclusive range. */
    public int range(int lo, int hi) {
        if (hi < lo) return lo;
        return lo + r.nextInt(hi - lo + 1);
    }

    public double nextDouble() {
        return r.nextDouble();
    }

    public boolean chance(double p) {
        return p >= 1 || (p > 0 && r.nextDouble() < p);
    }

    public long nextLong() {
        return r.nextLong();
    }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Stable hash of integer coordinates, used for deterministic per-cell decoration. */
    public static double hash01(long seed, int x, int y, int z) {
        long h = mix(seed ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L));
        return (h >>> 11) * 0x1.0p-53;
    }
}
