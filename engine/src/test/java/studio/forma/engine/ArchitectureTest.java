package studio.forma.engine;

import org.junit.jupiter.api.Test;
import studio.forma.engine.arch.Validator;
import studio.forma.engine.arch.Walk;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.Grid;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureTest {

    @Test
    void stairsConnectLevelsInTheirDirectionOnly() {
        Grid g = new Grid(6, 4, 1);
        g.set(0, 0, 0, Cell.FLOOR);
        g.set(1, 0, 0, Cell.STAIR_XP);
        g.set(2, 1, 0, Cell.STAIR_XP);
        g.set(3, 2, 0, Cell.FLOOR);
        g.set(4, 2, 0, Cell.FLOOR);
        BitSet r = Walk.reachable(g, new int[]{g.index(0, 0, 0)});
        assertTrue(r.get(g.index(4, 2, 0)), "a two-step stair climbs two levels");
        BitSet back = Walk.reachable(g, new int[]{g.index(4, 2, 0)});
        assertTrue(back.get(g.index(0, 0, 0)), "and can be walked down again");
        // a stair facing the wrong way does not connect
        Grid w = new Grid(4, 3, 1);
        w.set(0, 0, 0, Cell.FLOOR);
        w.set(1, 0, 0, Cell.STAIR_XN);
        w.set(2, 1, 0, Cell.FLOOR);
        assertFalse(Walk.reachable(w, new int[]{w.index(0, 0, 0)}).get(w.index(2, 1, 0)));
    }

    @Test
    void wallsAndMissingHeadroomBlockMovement() {
        Grid g = new Grid(5, 3, 1);
        for (int x = 0; x < 5; x++) g.set(x, 0, 0, Cell.TERRACE);
        g.set(2, 0, 0, Cell.WALL);
        assertFalse(Walk.reachable(g, new int[]{g.index(0, 0, 0)}).get(g.index(4, 0, 0)));
        g.set(2, 0, 0, Cell.TERRACE);
        g.set(3, 1, 0, Cell.ROOF);
        assertFalse(Walk.reachable(g, new int[]{g.index(0, 0, 0)}).get(g.index(3, 0, 0)), "a slab under a solid roof has no headroom");
    }

    @Test
    void coresConnectVertically() {
        Grid g = new Grid(3, 6, 1);
        for (int y = 0; y < 6; y++) g.set(1, y, 0, Cell.CORE);
        g.set(0, 0, 0, Cell.FLOOR);
        g.set(2, 4, 0, Cell.FLOOR);
        assertTrue(Walk.reachable(g, new int[]{g.index(0, 0, 0)}).get(g.index(2, 4, 0)));
    }

    @Test
    void connectivityReportsUnreachableRequiredSpaces() {
        Grid g = new Grid(8, 2, 1);
        Components comps = new Components();
        int a = comps.add("Hall", "room", Components.Material.SANDSTONE, true, false);
        int b = comps.add("Island", "room", Components.Material.SANDSTONE, true, false);
        for (int x = 0; x < 3; x++) g.set(x, 0, 0, Cell.FLOOR, a);
        for (int x = 5; x < 8; x++) g.set(x, 0, 0, Cell.FLOOR, b);
        Validator.Connectivity c = Validator.connectivity(g, comps, new int[]{g.index(0, 0, 0)});
        assertEquals(java.util.List.of("Island"), c.unreachableRequired());
        g.set(3, 0, 0, Cell.BRIDGE, a);
        g.set(4, 0, 0, Cell.BRIDGE, a);
        assertTrue(Validator.connectivity(g, comps, new int[]{g.index(0, 0, 0)}).unreachableRequired().isEmpty());
    }

    @Test
    void supportHeuristicFindsFloatingMassAndExemptsFantasy() {
        Grid g = new Grid(12, 8, 1);
        Components comps = new Components();
        int tower = comps.add("Tower", "tower", Components.Material.SANDSTONE, true, false);
        int floating = comps.add("Island", "platform", Components.Material.TIMBER, true, true);
        int slab = comps.add("Loose slab", "platform", Components.Material.TIMBER, true, false);
        for (int y = 0; y < 6; y++) g.set(1, y, 0, Cell.WALL, tower);
        for (int x = 2; x < 4; x++) g.set(x, 5, 0, Cell.TERRACE, tower); // short cantilever: fine
        g.set(9, 5, 0, Cell.TERRACE, slab);                                // nothing below: unsupported
        g.set(7, 6, 0, Cell.TERRACE, floating);                            // floating but exempt
        Validator.Support s = Validator.support(g, comps, 3, 8, 20);
        assertEquals(1, s.unsupported());
        assertEquals(java.util.List.of("Loose slab"), s.unsupportedComponents());
    }

    @Test
    void voidCheckDetectsCoveredAtrium() {
        Grid g = new Grid(5, 6, 5);
        for (int y = 1; y < 4; y++) g.set(2, y, 2, Cell.KEEP);
        assertEquals(0, Validator.voids(g).blockedColumns());
        g.set(2, 5, 2, Cell.ROOF);
        assertEquals(1, Validator.voids(g).blockedColumns());
    }
}
