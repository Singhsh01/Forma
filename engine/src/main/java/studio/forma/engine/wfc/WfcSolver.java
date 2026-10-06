package studio.forma.engine.wfc;

import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Rng;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Bounded tile-based Wave Function Collapse on a 3D lattice of tile slots.
 *
 * <ul>
 *   <li>Each slot's domain is a bitmask of allowed tiles.</li>
 *   <li>Explicit adjacency comes from {@link TileSet} socket compatibility.</li>
 *   <li>Boundary condition: a face on the lattice boundary must carry {@code boundarySocket}
 *       (or any socket when {@code boundarySocket == null}).</li>
 *   <li>Observation picks the slot with the lowest Shannon entropy; ties are broken by seeded
 *       noise. The tile is drawn by weight from the seeded stream.</li>
 *   <li>Propagation is arc consistency with a work queue.</li>
 *   <li>Contradiction recovery: restart from the initial constraints with a forked seed, at most
 *       {@code maxAttempts} times; then report failure. No unbounded backtracking.</li>
 * </ul>
 * Local adjacency does not guarantee global connectivity; callers must verify that separately.
 */
public final class WfcSolver {
    public record Result(boolean success, int[] tiles, int attempts, int observations, String failure) {}

    private final TileSet set;
    private final int nx, ny, nz;
    private final long[] initial;
    private final String boundarySocket;
    private final boolean boundaryBottom;

    public WfcSolver(TileSet set, int nx, int ny, int nz, String boundarySocket, boolean constrainBottom) {
        this.set = set;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.boundarySocket = boundarySocket;
        this.boundaryBottom = constrainBottom;
        this.initial = new long[nx * ny * nz];
        Arrays.fill(initial, set.allMask());
    }

    public int index(int x, int y, int z) {
        return x + nx * (z + nz * y);
    }

    /** Intersects a slot's initial domain with {@code mask}. */
    public void restrict(int x, int y, int z, long mask) {
        initial[index(x, y, z)] &= mask;
    }

    public Result solve(Rng rng, int maxAttempts, Cancellation cancel) {
        String lastFailure = "no attempts";
        int observations = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Rng r = rng.fork("wfc-attempt-" + attempt);
            long[] wave = initial.clone();
            String fail = applyBoundary(wave);
            if (fail == null) fail = propagateAll(wave);
            int obs = 0;
            while (fail == null) {
                cancel.check();
                int slot = lowestEntropy(wave, r);
                if (slot < 0) break; // fully collapsed
                long m = wave[slot];
                int t = pickWeighted(m, r);
                wave[slot] = 1L << t;
                obs++;
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(slot);
                fail = propagate(wave, q);
            }
            observations += obs;
            if (fail == null) {
                int[] tiles = new int[wave.length];
                for (int i = 0; i < wave.length; i++) tiles[i] = Long.numberOfTrailingZeros(wave[i]);
                return new Result(true, tiles, attempt, observations, null);
            }
            lastFailure = fail;
        }
        return new Result(false, null, maxAttempts, observations,
            "contradiction in every one of " + maxAttempts + " attempts (last: " + lastFailure + ")");
    }

    private String applyBoundary(long[] wave) {
        if (boundarySocket == null) return null;
        for (int y = 0; y < ny; y++)
            for (int z = 0; z < nz; z++)
                for (int x = 0; x < nx; x++) {
                    int i = index(x, y, z);
                    if (x == nx - 1) wave[i] &= set.maskWithSocket(TileSet.PX, boundarySocket);
                    if (x == 0) wave[i] &= set.maskWithSocket(TileSet.NX, boundarySocket);
                    if (z == nz - 1) wave[i] &= set.maskWithSocket(TileSet.PZ, boundarySocket);
                    if (z == 0) wave[i] &= set.maskWithSocket(TileSet.NZ, boundarySocket);
                    if (y == ny - 1) wave[i] &= set.maskWithSocket(TileSet.PY, boundarySocket);
                    if (y == 0 && boundaryBottom) wave[i] &= set.maskWithSocket(TileSet.NY, boundarySocket);
                    if (wave[i] == 0) return "boundary leaves slot (" + x + "," + y + "," + z + ") without tiles";
                }
        return null;
    }

    private String propagateAll(long[] wave) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < wave.length; i++) q.add(i);
        return propagate(wave, q);
    }

    private String propagate(long[] wave, ArrayDeque<Integer> q) {
        long[][] comp = set.compatible();
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % nx, z = (i / nx) % nz, y = i / (nx * nz);
            long m = wave[i];
            for (int d = 0; d < 6; d++) {
                int ax = x + TileSet.DX[d], ay = y + TileSet.DY[d], az = z + TileSet.DZ[d];
                if (ax < 0 || ay < 0 || az < 0 || ax >= nx || ay >= ny || az >= nz) continue;
                long allowed = 0;
                long mm = m;
                while (mm != 0) {
                    int t = Long.numberOfTrailingZeros(mm);
                    mm &= mm - 1;
                    allowed |= comp[d][t];
                }
                int j = index(ax, ay, az);
                long nw = wave[j] & allowed;
                if (nw != wave[j]) {
                    if (nw == 0) return "slot (" + ax + "," + ay + "," + az + ") lost all tiles";
                    wave[j] = nw;
                    q.add(j);
                }
            }
        }
        return null;
    }

    private int lowestEntropy(long[] wave, Rng r) {
        double best = Double.MAX_VALUE;
        int bestI = -1;
        for (int i = 0; i < wave.length; i++) {
            long m = wave[i];
            if (Long.bitCount(m) <= 1) continue;
            double sum = 0, sumLog = 0;
            long mm = m;
            while (mm != 0) {
                int t = Long.numberOfTrailingZeros(mm);
                mm &= mm - 1;
                double w = set.tile(t).weight();
                sum += w;
                sumLog += w * Math.log(w);
            }
            double entropy = Math.log(sum) - sumLog / sum + 1e-6 * r.nextDouble();
            if (entropy < best) {
                best = entropy;
                bestI = i;
            }
        }
        return bestI;
    }

    private int pickWeighted(long m, Rng r) {
        double sum = 0;
        long mm = m;
        while (mm != 0) {
            int t = Long.numberOfTrailingZeros(mm);
            mm &= mm - 1;
            sum += set.tile(t).weight();
        }
        double u = r.nextDouble() * sum;
        mm = m;
        int last = -1;
        while (mm != 0) {
            int t = Long.numberOfTrailingZeros(mm);
            mm &= mm - 1;
            last = t;
            u -= set.tile(t).weight();
            if (u <= 0) return t;
        }
        return last;
    }
}
