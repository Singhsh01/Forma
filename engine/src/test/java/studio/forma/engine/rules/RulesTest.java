package studio.forma.engine.rules;

import org.junit.jupiter.api.Test;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    static final Legend L = Legend.standard();

    static Grid row(String s) {
        Grid g = new Grid(s.length(), 1, 1);
        for (int x = 0; x < s.length(); x++) g.set(x, 0, 0, (byte) Cell.DEFAULT_CHARS.indexOf(s.charAt(x)));
        return g;
    }

    static String row(Grid g) {
        StringBuilder b = new StringBuilder();
        for (int x = 0; x < g.sx; x++) b.append(Cell.symbol(g.get(x, 0, 0)));
        return b.toString();
    }

    static RunContext ctx(Grid g, long seed) {
        return new RunContext(g, new Rng(seed), new RuleLog(), Cancellation.none(), null, 100_000);
    }

    @Test
    void parsesLayersRowsAndColumns() {
        Pattern p = Pattern.parse("AB/CD EF/GT", "WW/WW WW/WW", L);
        assertEquals(2, p.nx);
        assertEquals(2, p.nz);
        assertEquals(2, p.ny);
        assertEquals(1 << Cell.AIR, p.inputAt(0, 0, 0));
        assertEquals(1 << Cell.TERRACE, p.inputAt(1, 1, 1));
    }

    @Test
    void wildcardMatchesAnythingAndKeepsCells() {
        Grid g = row("AWE");
        try (RunContext c = ctx(g, 1)) {
            Node n = new ParallelNode("n", List.of(Rule.of("r", "*W*", "N*N").sym(Symmetry.NONE).build(L)), 1);
            c.run(n);
        }
        assertEquals("NWN", row(g), "wildcard input matched both ends; '*' output kept the middle");
    }

    @Test
    void validationErrorsAreSpecific() {
        var e1 = assertThrows(RuleValidationException.class, () -> Pattern.parse("AB", "A", L));
        assertTrue(e1.getMessage().contains("same size"), e1.getMessage());
        var e2 = assertThrows(RuleValidationException.class, () -> Pattern.parse("A", ".", L));
        assertTrue(e2.getMessage().contains("cannot be used as an output"), e2.getMessage());
        var e3 = assertThrows(RuleValidationException.class, () -> Pattern.parse("A", "*", L));
        assertTrue(e3.getMessage().contains("never changes"), e3.getMessage());
        var e4 = assertThrows(RuleValidationException.class, () -> Pattern.parse("Aq", "AA", L));
        assertTrue(e4.getMessage().contains("unknown input symbol 'q'"), e4.getMessage());
        var e5 = assertThrows(RuleValidationException.class, () -> new Rule("x", "", "A", "W", 1.5, Symmetry.NONE, L));
        assertTrue(e5.getMessage().contains("p must be"), e5.getMessage());
    }

    @Test
    void rotationsAndReflectionsProduceDistinctVariants() {
        Pattern p = Pattern.parse("AW", "WA", L);
        Pattern r4 = p.rotatedY().rotatedY().rotatedY().rotatedY();
        assertTrue(p.sameAs(r4), "four quarter turns are the identity");
        assertEquals(1, p.variants(Symmetry.NONE).size());
        assertEquals(2, p.variants(Symmetry.MIRROR_X).size());
        assertEquals(4, p.variants(Symmetry.ROTATE_Y).size());
        assertEquals(4, p.variants(Symmetry.FULL).size(), "mirror of AW equals its half turn, so duplicates are removed");
        Pattern l = Pattern.parse("AW/AE", "WW/WW", L);
        assertEquals(8, l.variants(Symmetry.FULL).size(), "an L-shaped pattern has eight distinct orientations");
        assertEquals(1, Pattern.parse("A", "W", L).variants(Symmetry.FULL).size());
    }

    @Test
    void rotationMapsPlusXToPlusZ() {
        Pattern p = Pattern.parse("AW", "WW", L); // A at x=0, W at x=1 (+x side)
        Pattern r = p.rotatedY();
        assertEquals(1, r.nx);
        assertEquals(2, r.nz);
        assertEquals(1 << Cell.WALL, r.inputAt(0, 0, 1), "the +x cell moves to +z after a quarter turn");
    }

    @Test
    void patternsNeverWrapAroundTheBoundary() {
        Grid g = row("WAAAW");
        try (RunContext c = ctx(g, 3)) {
            // "WA" with a rotation could only match across the edge if wrapping were allowed
            c.run(new ParallelNode("n", List.of(Rule.of("r", "AW", "NW").sym(Symmetry.NONE).build(L)), 0));
        }
        assertEquals("WAANW", row(g));
    }

    @Test
    void oneNodeIsDeterministicForASeed() {
        String a = growth(42), b = growth(42), c = growth(43);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    private String growth(long seed) {
        Grid g = new Grid(30, 1, 30);
        g.set(15, 0, 15, Cell.GRASS);
        for (int z = 0; z < 30; z++) for (int x = 0; x < 30; x++) if (g.get(x, 0, z) == Cell.EMPTY) g.set(x, 0, z, Cell.TERRACE);
        try (RunContext c = ctx(g, seed)) {
            c.run(new OneNode("grow", List.of(Rule.of("g", "GT", "GG").build(L)), 200));
        }
        assertEquals(201, g.count(Cell.GRASS), "200 steps of Eden growth add exactly 200 cells");
        StringBuilder sb = new StringBuilder();
        for (int z = 0; z < 30; z++) sb.append(g.layerString(0));
        return sb.toString();
    }

    @Test
    void allNodeAppliesAMaximalNonOverlappingSet() {
        for (long seed = 0; seed < 20; seed++) {
            Grid g = row("BBBBB");
            try (RunContext c = ctx(g, seed)) {
                Node n = new AllNode("pairs", List.of(Rule.of("p", "BB", "AA").sym(Symmetry.NONE).build(L)), 1);
                n.reset();
                assertTrue(n.step(c));
            }
            assertEquals(4, row(g).chars().filter(ch -> ch == 'A').count(), "any maximal set of disjoint pairs in 5 cells has two pairs");
        }
    }

    @Test
    void parallelProbabilityIsSeeded() {
        Grid g1 = new Grid(40, 1, 40), g2 = new Grid(40, 1, 40);
        for (Grid g : List.of(g1, g2)) for (int i = 0; i < g.size(); i++) g.setIndex(i, Cell.TERRACE);
        Rule r = Rule.of("s", "T", "G").p(0.3).build(L);
        try (RunContext c = ctx(g1, 9)) { c.run(new ParallelNode("s", List.of(r), 1)); }
        try (RunContext c = ctx(g2, 9)) { c.run(new ParallelNode("s", List.of(r), 1)); }
        assertEquals(g1.fingerprint(), g2.fingerprint());
        int n = g1.count(Cell.GRASS);
        assertTrue(n > 1600 * 0.22 && n < 1600 * 0.38, "about 30% of cells converted, got " + n);
    }

    @Test
    void markovNodePrefersEarlierChildren() {
        // MazeBacktracker-style: extend while possible, otherwise backtrack.
        Grid g = row("RBBBB");
        try (RunContext c = ctx(g, 1)) {
            Node m = new MarkovNode("m", List.of(
                new OneNode("extend", List.of(Rule.of("e", "RB", "GR").sym(Symmetry.NONE).build(L)), 0),
                new OneNode("never", List.of(Rule.of("n", "GR", "GG").sym(Symmetry.NONE).build(L)), 0)));
            c.run(m);
        }
        // extend runs until the walker reaches the end; then the second rule fires once
        assertEquals("GGGGG", row(g));
    }

    @Test
    void sequenceRunsChildrenInOrder() {
        Grid g = row("TTTT");
        try (RunContext c = ctx(g, 1)) {
            Node s = new SequenceNode("s", List.of(
                new ParallelNode("a", List.of(Rule.of("a", "T", "G").sym(Symmetry.NONE).build(L)), 0),
                new ParallelNode("b", List.of(Rule.of("b", "G", "V").sym(Symmetry.NONE).build(L)), 0)));
            c.run(s);
        }
        assertEquals("VVVV", row(g));
    }

    @Test
    void stepLimitAndCancellationStopRuns() {
        Grid g = new Grid(20, 1, 20);
        for (int i = 0; i < g.size(); i++) g.setIndex(i, Cell.TERRACE);
        g.setIndex(0, Cell.GRASS);
        try (RunContext c = new RunContext(g, new Rng(1), new RuleLog(), Cancellation.none(), null, 15)) {
            c.run(new OneNode("g", List.of(Rule.of("g", "GT", "GG").build(L)), 0));
            assertTrue(c.hitStepLimit());
            assertEquals(15, c.steps());
        }
        Cancellation cancel = new Cancellation();
        cancel.cancel();
        try (RunContext c = new RunContext(g, new Rng(1), new RuleLog(), cancel, null, 1000)) {
            assertThrows(Cancellation.CancelledException.class, () -> c.run(new OneNode("g", List.of(Rule.of("g", "GT", "GG").build(L)), 0)));
        }
    }

    /** The incremental match cache must never miss a valid match, even when other code edits the grid. */
    @Test
    void incrementalMatchingAgreesWithFullScan() {
        Rng chaos = new Rng(77);
        Grid g = new Grid(24, 3, 24);
        for (int i = 0; i < g.size(); i++) g.setIndex(i, chaos.chance(0.5) ? Cell.TERRACE : Cell.WALL);
        g.set(5, 1, 5, Cell.GRASS);
        Rule grow = Rule.of("g", "GT", "GG").build(L);
        Rule up = Rule.of("u", "G T", "G G").sym(Symmetry.NONE).build(L);
        OneNode node = new OneNode("g", List.of(grow, up), 0);
        try (RunContext c = ctx(g, 5)) {
            node.reset();
            for (int step = 0; step < 300; step++) {
                // an outside edit between steps, like another node in a Markov program
                int i = chaos.nextInt(g.size());
                g.setIndex(i, chaos.chance(0.5) ? Cell.TERRACE : Cell.GRASS);
                boolean progressed = node.step(c);
                if (!progressed) break;
                node.refreshForTest(c);
                Set<Long> valid = fullScan(node, c);
                Set<Long> cached = node.cachedKeysForTest();
                for (long k : valid) assertTrue(cached.contains(k), "cache missed a valid match at step " + step);
            }
        }
    }

    private Set<Long> fullScan(OneNode node, RunContext c) {
        Set<Long> s = new HashSet<>();
        RuleNode.IntList all = node.scanAll(c);
        for (int i = 0; i < all.size(); i += 2) s.add(((long) all.get(i) << 32) | (all.get(i + 1) & 0xffffffffL));
        return s;
    }

    @Test
    void ruleLogCountsApplicationsAndCapturesASample() {
        Grid g = row("TTTGTTT");
        RuleLog log = new RuleLog();
        log.setStage("s");
        try (RunContext c = new RunContext(g, new Rng(2), log, Cancellation.none(), null, 1000)) {
            c.run(new OneNode("grow", List.of(Rule.of("g", "GT", "GG").build(L)), 0));
        }
        assertEquals(6, log.applicationsOf("g"));
        var rec = log.rules().get(0);
        assertNotNull(rec.sample);
        assertEquals(rec.sample.before().length, rec.sample.after().length);
    }

    @Test
    void programParserReportsLineNumbers() {
        String bad = "sequence s\n  one a\n    rule x \"T\" -> \"Q\"\n";
        var e = assertThrows(RuleValidationException.class, () -> ProgramParser.parse(bad, L));
        assertTrue(e.getMessage().startsWith("line 3"), e.getMessage());
        var e2 = assertThrows(RuleValidationException.class, () -> ProgramParser.parse("sequence s\n   prl a\n", L));
        assertTrue(e2.getMessage().contains("multiple of two"), e2.getMessage());
        var e3 = assertThrows(RuleValidationException.class, () -> ProgramParser.parse("loop s\n", L));
        assertTrue(e3.getMessage().contains("unknown node type"), e3.getMessage());
        Node ok = ProgramParser.parse("sequence s  # top\n  prl a steps=1\n    rule r \"T E\" -> \"G E\" p=0.5 sym=none  # seed\n", L);
        assertEquals(1, ProgramParser.rulesOf(ok).size());
        assertEquals("seed", ProgramParser.rulesOf(ok).get(0).description);
    }
}
