package studio.forma.engine;

import org.junit.jupiter.api.Test;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Rng;
import studio.forma.engine.mcmc.MetropolisSampler;
import studio.forma.engine.wfc.TileSet;
import studio.forma.engine.wfc.WfcSolver;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WfcAndMcmcTest {

    /** A path tileset: straight, turn, end and empty tiles with "p" (path) and "e" (empty) sockets. */
    static TileSet paths() {
        return new TileSet()
            .add("Empty", 3, "e", "e", "e", "e", "e", "e", false)
            .add("Line", 1, "p", "p", "e", "e", "e", "e", true)
            .add("Turn", 1, "p", "e", "e", "e", "p", "e", true)
            .add("Tee", 0.3, "p", "p", "e", "e", "p", "e", true);
    }

    @Test
    void rotationsAreDeduplicated() {
        TileSet t = paths();
        assertEquals(1, Long.bitCount(t.maskOfBase("Empty")));
        assertEquals(2, Long.bitCount(t.maskOfBase("Line")), "a straight piece has two distinct orientations");
        assertEquals(4, Long.bitCount(t.maskOfBase("Turn")));
    }

    @Test
    void solutionsRespectAdjacencyAndBoundary() {
        TileSet t = paths();
        for (long seed = 1; seed <= 12; seed++) {
            WfcSolver s = new WfcSolver(t, 9, 1, 9, "e", true);
            WfcSolver.Result r = s.solve(new Rng(seed), 10, Cancellation.none());
            assertTrue(r.success(), r.failure());
            int[] tiles = r.tiles();
            for (int z = 0; z < 9; z++)
                for (int x = 0; x < 9; x++) {
                    String[] so = t.tile(tiles[s.index(x, 0, z)]).sockets();
                    if (x == 8) assertEquals("e", so[TileSet.PX]);
                    if (x == 0) assertEquals("e", so[TileSet.NX]);
                    if (z == 8) assertEquals("e", so[TileSet.PZ]);
                    if (z == 0) assertEquals("e", so[TileSet.NZ]);
                    if (x < 8) assertEquals(so[TileSet.PX], t.tile(tiles[s.index(x + 1, 0, z)]).sockets()[TileSet.NX]);
                    if (z < 8) assertEquals(so[TileSet.PZ], t.tile(tiles[s.index(x, 0, z + 1)]).sockets()[TileSet.NZ]);
                }
        }
    }

    @Test
    void constraintsPropagateFromARestrictedSlot() {
        TileSet t = paths();
        WfcSolver s = new WfcSolver(t, 5, 1, 5, "e", true);
        // force a horizontal line in the middle: its neighbours along x must accept a path socket
        s.restrict(2, 0, 2, t.maskOfBase("Line") & t.maskWithSocket(TileSet.PX, "p"));
        WfcSolver.Result r = s.solve(new Rng(3), 5, Cancellation.none());
        assertTrue(r.success());
        assertEquals("p", t.tile(r.tiles()[s.index(3, 0, 2)]).sockets()[TileSet.NX]);
        assertEquals("p", t.tile(r.tiles()[s.index(1, 0, 2)]).sockets()[TileSet.PX]);
    }

    @Test
    void contradictionsAreReportedAfterBoundedRetries() {
        TileSet t = paths();
        WfcSolver s = new WfcSolver(t, 3, 1, 3, "e", true);
        // a path must leave the left edge, but the boundary forbids it: impossible
        s.restrict(0, 0, 1, t.maskWithSocket(TileSet.NX, "p"));
        WfcSolver.Result r = s.solve(new Rng(1), 4, Cancellation.none());
        assertFalse(r.success());
        assertEquals(4, r.attempts());
        assertTrue(r.failure().contains("contradiction"), r.failure());
    }

    @Test
    void wfcIsDeterministicPerSeed() {
        TileSet t = paths();
        int[] a = new WfcSolver(t, 8, 1, 8, "e", true).solve(new Rng(5), 5, Cancellation.none()).tiles();
        int[] b = new WfcSolver(t, 8, 1, 8, "e", true).solve(new Rng(5), 5, Cancellation.none()).tiles();
        assertArrayEquals(a, b);
    }

    // ------------------------------------------------------------------ MCMC

    /**
     * States 0..N-1 with energy E(x) = x, symmetric +/-1 proposals (out-of-range moves rejected by a
     * hard constraint). At T = 1 the stationary distribution is pi(x) = e^-x / Z, which the
     * empirical visit frequencies must match.
     */
    @Test
    void metropolisSamplesTheBoltzmannDistribution() {
        int n = 6;
        double t = 1.0;
        MetropolisSampler<Integer> s = new MetropolisSampler<>(
            (x, rng) -> MetropolisSampler.Proposal.symmetric(x + (rng.nextInt(2) == 0 ? 1 : -1), "step"),
            x -> new MetropolisSampler.Breakdown(x, Map.of("e", (double) x)),
            List.of(x -> x < 0 || x >= n ? "out of range" : null));
        int iters = 400_000;
        long[] counts = new long[n];
        // run in chunks and record where the chain sits after each chunk step
        Rng rng = new Rng(2024);
        int x = 3;
        for (int k = 0; k < iters; k++) {
            var r = s.run(x, 1, MetropolisSampler.Schedule.fixed(t), rng, null, null);
            x = r.finalState();
            counts[x]++;
        }
        double z = 0;
        for (int i = 0; i < n; i++) z += Math.exp(-i / t);
        for (int i = 0; i < n; i++) {
            double expected = Math.exp(-i / t) / z;
            double observed = (double) counts[i] / iters;
            assertEquals(expected, observed, 0.012, "state " + i);
        }
    }

    @Test
    void uphillMovesAreAcceptedWithTheRightProbability() {
        // from 0 to 1 costs dE = 1 at T = 0.5: acceptance e^-2
        MetropolisSampler<Integer> s = new MetropolisSampler<>(
            (x, rng) -> MetropolisSampler.Proposal.symmetric(1, "up"),
            x -> new MetropolisSampler.Breakdown(x, Map.of()), List.of());
        int accepted = 0, trials = 60_000;
        Rng rng = new Rng(9);
        for (int i = 0; i < trials; i++) accepted += s.run(0, 1, MetropolisSampler.Schedule.fixed(0.5), rng, null, null).accepted();
        assertEquals(Math.exp(-2), (double) accepted / trials, 0.006);
    }

    @Test
    void hardConstraintsRejectAndAnnealingIsLabelled() {
        MetropolisSampler<Integer> s = new MetropolisSampler<>(
            (x, rng) -> MetropolisSampler.Proposal.symmetric(x + 1, "inc"),
            x -> new MetropolisSampler.Breakdown(-x, Map.of()),
            List.of(x -> x > 3 ? "too big" : null));
        var r = s.run(0, 50, MetropolisSampler.Schedule.annealing(1, 0.01), new Rng(1), null, null);
        assertEquals(3, r.finalState());
        assertTrue(r.rejectedByConstraint() >= 40);
        assertEquals("simulated-annealing", r.mode());
        assertEquals("mcmc", MetropolisSampler.Schedule.fixed(1).mode());
        assertThrows(IllegalArgumentException.class, () -> s.run(9, 1, MetropolisSampler.Schedule.fixed(1), new Rng(1), null, null));
    }

    @Test
    void cancellationStopsTheChain() {
        Cancellation c = new Cancellation();
        c.cancel();
        MetropolisSampler<Integer> s = new MetropolisSampler<>((x, rng) -> MetropolisSampler.Proposal.symmetric(x, "stay"),
            x -> new MetropolisSampler.Breakdown(0, Map.of()), List.of());
        var r = s.run(0, 1000, MetropolisSampler.Schedule.fixed(1), new Rng(1), c, null);
        assertTrue(r.cancelled());
        assertEquals(0, r.iterations());
    }
}
