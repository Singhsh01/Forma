package studio.forma.engine.arch;

import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.Grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Architectural checks run after generation. Connectivity and occupancy checks are exact on the
 * grid; support and daylight checks are explicitly heuristics for a conceptual generator.
 */
public final class Validator {
    private Validator() {}

    public record Connectivity(BitSet reached, int standable, int reachable, List<String> unreachableRequired) {}

    public static Connectivity connectivity(Grid g, Components comps, int[] entrances) {
        BitSet reached = Walk.reachable(g, entrances);
        int standable = 0, reachable = 0;
        boolean[] compReached = new boolean[comps.size()];
        boolean[] compHasStand = new boolean[comps.size()];
        for (int i = 0; i < g.size(); i++) {
            byte s = g.getIndex(i);
            if (!Cell.isStandable(s)) continue;
            standable++;
            int c = g.compIndex(i);
            if (c < compHasStand.length) compHasStand[c] = true;
            if (reached.get(i)) {
                reachable++;
                if (c < compReached.length) compReached[c] = true;
            }
        }
        List<String> missing = new ArrayList<>();
        for (Components.Component c : comps.all())
            if (c.required() && compHasStand[c.id()] && !compReached[c.id()]) missing.add(c.name());
        return new Connectivity(reached, standable, reachable, missing);
    }

    /**
     * Support heuristic. A massive or walkable cell counts as supported when it rests on a supported
     * load-bearing cell, or when it is within {@code cantilever} cells (horizontally, same level) of
     * such a cell through other structural cells. Bridge decks may span up to {@code bridgeSpan}.
     * Components flagged as floating (fantasy mode) are excluded and reported separately.
     */
    public record Support(int structural, int unsupported, List<String> unsupportedComponents, List<String> examples) {}

    public static Support support(Grid g, Components comps, int cantilever, int slabSpan, int bridgeSpan) {
        int n = g.size();
        BitSet sup = new BitSet(n);
        int structural = 0;
        int[] dist = new int[g.sx * g.sz];
        for (int y = 0; y < g.sy; y++) {
            ArrayDeque<Integer> q = new ArrayDeque<>();
            java.util.Arrays.fill(dist, Integer.MAX_VALUE);
            for (int z = 0; z < g.sz; z++)
                for (int x = 0; x < g.sx; x++) {
                    int i = g.index(x, y, z);
                    byte s = g.getIndex(i);
                    if (!isStructural(s)) continue;
                    boolean on;
                    if (y == 0) on = true;
                    else {
                        int below = g.index(x, y - 1, z);
                        byte b = g.getIndex(below);
                        on = sup.get(below) && (Cell.isLoadBearing(b) || Cell.isStandable(b) || Cell.isSolid(b));
                        // a branch of a branching column rests on the trunk cell diagonally below it
                        // branches of a branching column, and the corbelled steps of a stepped vault or roof,
                        // rest on the matching cell diagonally below them
                        if (!on && (s == Cell.TRUNK || s == Cell.ROOF || s == Cell.GLASS))
                            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                                int bx = x + d[0], bz = z + d[1];
                                if (bx < 0 || bz < 0 || bx >= g.sx || bz >= g.sz) continue;
                                int bi = g.index(bx, y - 1, bz);
                                byte bs = g.getIndex(bi);
                                if (sup.get(bi) && (s == Cell.TRUNK ? (bs == Cell.TRUNK || bs == Cell.COLUMN) : (bs == Cell.ROOF || bs == Cell.GLASS || bs == Cell.WALL))) on = true;
                            }
                    }
                    if (on) {
                        sup.set(i);
                        dist[x + g.sx * z] = 0;
                        q.add(x + g.sx * z);
                    }
                }
            while (!q.isEmpty()) {
                int c = q.poll();
                int x = c % g.sx, z = c / g.sx;
                int d0 = dist[c];
                int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] o : nb) {
                    int nx = x + o[0], nz = z + o[1];
                    if (nx < 0 || nz < 0 || nx >= g.sx || nz >= g.sz) continue;
                    int i = g.index(nx, y, nz);
                    byte s = g.getIndex(i);
                    if (!isStructural(s) || sup.get(i)) continue;
                    int limit = s == Cell.BRIDGE ? bridgeSpan : (isSlab(s) || s == Cell.ARCH) ? slabSpan : cantilever;
                    if (d0 + 1 > limit) continue;
                    sup.set(i);
                    dist[nx + g.sx * nz] = d0 + 1;
                    q.add(nx + g.sx * nz);
                }
            }
        }
        int unsupported = 0;
        java.util.Set<String> names = new java.util.TreeSet<>();
        List<String> examples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte s = g.getIndex(i);
            if (!isStructural(s)) continue;
            Components.Component c = comps.get(g.compIndex(i));
            if (c.floating()) continue;
            structural++;
            if (!sup.get(i)) {
                unsupported++;
                names.add(c.name());
                if (examples.size() < 4) examples.add(Cell.name(s) + " at (" + g.xOf(i) + "," + g.yOf(i) + "," + g.zOf(i) + ")");
            }
        }
        return new Support(structural, unsupported, new ArrayList<>(names), examples);
    }

    static boolean isSlab(byte s) {
        return s == Cell.FLOOR || s == Cell.TERRACE || s == Cell.GRASS || s == Cell.SHELF || s == Cell.ROOF
            || s == Cell.PATH || s == Cell.DOOR || s == Cell.GLASS || s == Cell.CANOPY || s == Cell.WATER;
    }

    static boolean isStructural(byte s) {
        return Cell.isLoadBearing(s) || Cell.isStandable(s) || s == Cell.GLASS || s == Cell.WATER || s == Cell.CANOPY;
    }

    /** Counts reserved-void cells and checks that each void column is open to the sky. */
    public record Voids(int keepCells, int blockedColumns) {}

    public static Voids voids(Grid g) {
        int keep = 0, blocked = 0;
        for (int z = 0; z < g.sz; z++)
            for (int x = 0; x < g.sx; x++) {
                int top = -1;
                for (int y = g.sy - 1; y >= 0; y--) if (g.get(x, y, z) == Cell.KEEP) { top = y; break; }
                if (top < 0) continue;
                for (int y = 0; y < g.sy; y++) if (g.get(x, y, z) == Cell.KEEP) keep++;
                for (int y = top + 1; y < g.sy; y++) {
                    byte s = g.get(x, y, z);
                    if (Cell.isSolid(s) || s == Cell.BRIDGE || Cell.isStandable(s)) { blocked++; break; }
                }
            }
        return new Voids(keep, blocked);
    }

    /** Fraction of walkable outdoor cells with open sky above (daylight proxy, heuristic). */
    public static double skyAccess(Grid g) {
        int stand = 0, open = 0;
        for (int z = 0; z < g.sz; z++)
            for (int x = 0; x < g.sx; x++) {
                boolean covered = false;
                for (int y = g.sy - 1; y >= 0; y--) {
                    byte s = g.get(x, y, z);
                    if (Cell.isStandable(s) && s != Cell.FLOOR && s != Cell.SHELF && s != Cell.CORE) {
                        stand++;
                        if (!covered) open++;
                    }
                    if (Cell.isSolid(s) || s == Cell.FLOOR || s == Cell.TERRACE || s == Cell.BRIDGE || s == Cell.GLASS) covered = true;
                }
            }
        return stand == 0 ? 1 : (double) open / stand;
    }

    /** Walkable cells whose headroom is blocked. */
    public static int clearanceViolations(Grid g) {
        return clearanceExamples(g, null);
    }

    /** Counts headroom violations and collects up to five example coordinates. */
    public static int clearanceExamples(Grid g, List<String> examples) {
        int v = 0;
        for (int y = 0; y < g.sy - 1; y++)
            for (int z = 0; z < g.sz; z++)
                for (int x = 0; x < g.sx; x++) {
                    byte s = g.get(x, y, z);
                    byte above = g.get(x, y + 1, z);
                    // posts and trunks standing on a slab are its structure, not an obstruction over a walkway
                    if (above == Cell.COLUMN || above == Cell.TRUNK) continue;
                    if ((s == Cell.BRIDGE || s == Cell.TERRACE || s == Cell.PATH || Cell.isStair(s)) && Cell.isSolid(above)) {
                        v++;
                        if (examples != null && examples.size() < 5)
                            examples.add(Cell.name(s) + " under " + Cell.name(g.get(x, y + 1, z)) + " at (" + x + "," + y + "," + z + ")");
                    }
                }
        return v;
    }
}
