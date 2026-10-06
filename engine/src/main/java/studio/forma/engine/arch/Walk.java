package studio.forma.engine.arch;

import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Grid;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.function.IntConsumer;

/**
 * Pedestrian movement graph over a grid, used for connectivity validation.
 *
 * <p>A person stands at the bottom of a standable cell. They may step horizontally between
 * standable cells on the same level when the cell above is not solid (headroom). A stair cell
 * rising toward direction d is entered from its back at the same level and left at its front one
 * level up; consecutive stairs chain diagonally. Circulation cores connect vertically. Local
 * adjacency rules (including WFC tiles) do not imply global connectivity, which is why every
 * preset is checked with this traversal.
 */
public final class Walk {
    private Walk() {}

    private static final int[] HX = {1, -1, 0, 0};
    private static final int[] HZ = {0, 0, 1, -1};

    static int stairDir(byte s) {
        return switch (s) {
            case Cell.STAIR_XP -> 0;
            case Cell.STAIR_XN -> 1;
            case Cell.STAIR_ZP -> 2;
            case Cell.STAIR_ZN -> 3;
            default -> -1;
        };
    }

    static boolean headroom(Grid g, int x, int y, int z) {
        byte above = g.get(x, y + 1, z);
        return !Cell.isSolid(above) || y + 1 >= g.sy;
    }

    static boolean plain(Grid g, int x, int y, int z) {
        byte s = g.get(x, y, z);
        return Cell.isStandable(s) && !Cell.isStair(s) && headroom(g, x, y, z);
    }

    /** Calls {@code out} with the grid index of every cell reachable in one move from (x,y,z). */
    public static void neighbours(Grid g, int x, int y, int z, IntConsumer out) {
        byte s = g.get(x, y, z);
        int sd = stairDir(s);
        if (sd >= 0) {
            int dx = HX[sd], dz = HZ[sd];
            // back, same level
            if (plain(g, x - dx, y, z - dz)) out.accept(g.index(x - dx, y, z - dz));
            else if (stairDir(g.get(x - dx, y - 1, z - dz)) == sd) out.accept(g.index(x - dx, y - 1, z - dz));
            // front, one level up
            byte f = g.get(x + dx, y + 1, z + dz);
            if (g.inBounds(x + dx, y + 1, z + dz) && (plain(g, x + dx, y + 1, z + dz) || stairDir(f) == sd))
                out.accept(g.index(x + dx, y + 1, z + dz));
            return;
        }
        if (!Cell.isStandable(s)) return;
        for (int d = 0; d < 4; d++) {
            int nx = x + HX[d], nz = z + HZ[d];
            if (!g.inBounds(nx, y, nz)) continue;
            byte n = g.get(nx, y, nz);
            if (plain(g, nx, y, nz)) out.accept(g.index(nx, y, nz));
            else if (stairDir(n) == d) out.accept(g.index(nx, y, nz));          // enter a stair at its back
            // arrive from a stair below whose front is here: stair at (x - d, y - 1) rising toward d
            int bx = x - HX[d], bz = z - HZ[d];
            if (g.inBounds(bx, y - 1, bz) && stairDir(g.get(bx, y - 1, bz)) == d) out.accept(g.index(bx, y - 1, bz));
        }
        if (s == Cell.CORE) {
            if (g.get(x, y + 1, z) == Cell.CORE) out.accept(g.index(x, y + 1, z));
            if (g.get(x, y - 1, z) == Cell.CORE) out.accept(g.index(x, y - 1, z));
        }
    }

    /** Breadth-first reachability from the given start cells. */
    public static BitSet reachable(Grid g, int[] starts) {
        BitSet seen = new BitSet(g.size());
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s : starts) {
            if (s >= 0 && s < g.size() && !seen.get(s)) {
                seen.set(s);
                q.add(s);
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            neighbours(g, g.xOf(i), g.yOf(i), g.zOf(i), j -> {
                if (!seen.get(j)) {
                    seen.set(j);
                    q.add(j);
                }
            });
        }
        return seen;
    }
}
