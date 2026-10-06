package studio.forma.engine.arch;

import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.Grid;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Room and circulation graph derived from the finished grid: a node per component that people can
 * stand in, an edge wherever one walking move crosses from one component into another, labelled
 * by how it is crossed (door, bridge, stair, open). Derived, not authored, so it always agrees with
 * the geometry.
 */
public final class RoomGraph {
    public record Node(int id, String name, String kind, int cells, boolean reachable, double x, double y, double z) {}

    public record Edge(int a, int b, String via, int crossings) {}

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();

    public static RoomGraph build(Grid g, Components comps, BitSet reached) {
        int n = comps.size();
        int[] cells = new int[n];
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        boolean[] reach = new boolean[n];
        Map<Long, int[]> edges = new LinkedHashMap<>();
        Map<Long, String> via = new LinkedHashMap<>();
        for (int i = 0; i < g.size(); i++) {
            byte s = g.getIndex(i);
            if (!Cell.isStandable(s)) continue;
            int c = g.compIndex(i);
            int x = g.xOf(i), y = g.yOf(i), z = g.zOf(i);
            cells[c]++;
            sx[c] += x; sy[c] += y; sz[c] += z;
            if (reached != null && reached.get(i)) reach[c] = true;
            final int ci = c;
            final byte fs = s;
            Walk.neighbours(g, x, y, z, j -> {
                int cj = g.compIndex(j);
                if (cj == ci) return;
                int lo = Math.min(ci, cj), hi = Math.max(ci, cj);
                long key = ((long) lo << 32) | hi;
                edges.computeIfAbsent(key, k -> new int[1])[0]++;
                byte t = g.getIndex(j);
                String v = kindOf(fs, t);
                via.merge(key, v, (old, nw) -> rank(nw) > rank(old) ? nw : old);
            });
        }
        RoomGraph rg = new RoomGraph();
        for (int c = 0; c < n; c++) {
            if (cells[c] == 0) continue;
            Components.Component comp = comps.get(c);
            rg.nodes.add(new Node(c, comp.name(), comp.kind(), cells[c], reach[c],
                sx[c] / cells[c] - g.sx / 2.0 + 0.5, sy[c] / cells[c], sz[c] / cells[c] - g.sz / 2.0 + 0.5));
        }
        for (var e : edges.entrySet()) {
            long k = e.getKey();
            rg.edges.add(new Edge((int) (k >>> 32), (int) k, via.get(k), e.getValue()[0] / 2 + e.getValue()[0] % 2));
        }
        return rg;
    }

    private static String kindOf(byte a, byte b) {
        if (a == Cell.DOOR || b == Cell.DOOR) return "door";
        if (a == Cell.BRIDGE || b == Cell.BRIDGE) return "bridge";
        if (Cell.isStair(a) || Cell.isStair(b)) return "stair";
        if (a == Cell.CORE || b == Cell.CORE) return "core";
        return "open";
    }

    private static int rank(String v) {
        return switch (v) {
            case "bridge" -> 4;
            case "stair" -> 3;
            case "door" -> 2;
            case "core" -> 1;
            default -> 0;
        };
    }
}
