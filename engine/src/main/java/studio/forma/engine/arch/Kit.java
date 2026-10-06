package studio.forma.engine.arch;

import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;

/**
 * Constructive building operations shared by presets: rasterising volumes into storeyed shells,
 * cutting doors, laying bridges, stairs, circulation cores and terrain. Every write goes through
 * the grid, so the history records constructive stages exactly like rule stages.
 */
public final class Kit {
    public final Grid g;
    private int conflicts;

    public Kit(Grid g) {
        this.g = g;
    }

    public int conflicts() {
        return conflicts;
    }

    public void box(int x0, int y0, int z0, int x1, int y1, int z1, byte s, int comp) {
        for (int y = Math.max(0, y0); y < Math.min(g.sy, y1); y++)
            for (int z = Math.max(0, z0); z < Math.min(g.sz, z1); z++)
                for (int x = Math.max(0, x0); x < Math.min(g.sx, x1); x++) g.set(x, y, z, s, comp);
    }

    public void put(int x, int y, int z, byte s, int comp) {
        g.set(x, y, z, s, comp);
    }

    /** Writes only where the cell is currently open air (never overwrites mass). */
    public boolean putIfOpen(int x, int y, int z, byte s, int comp) {
        if (!g.inBounds(x, y, z)) return false;
        byte cur = g.get(x, y, z);
        if (cur == Cell.EMPTY || cur == Cell.AIR) {
            g.set(x, y, z, s, comp);
            return true;
        }
        return false;
    }

    public interface Footprint {
        boolean covers(int x, int z);
    }

    /**
     * Rasterises a storeyed building shell: each storey is two cells tall; edge cells become wall
     * piers or window bays (marker {@link Cell#MARK_A}) in a regular rhythm aligned across storeys;
     * interior cells get a floor slab and air above. The roof level receives {@code roof}.
     * Cells already occupied by another solid are counted as conflicts and left alone.
     */
    public void shell(Massing.Volume v, Footprint fp, int comp, byte roof, int bayPeriod) {
        int x0 = Math.max(0, v.x0() - 1), x1 = Math.min(g.sx, v.x1() + 1);
        int z0 = Math.max(0, v.z0() - 1), z1 = Math.min(g.sz, v.z1() + 1);
        for (int z = z0; z < z1; z++)
            for (int x = x0; x < x1; x++) {
                if (!fp.covers(x, z)) continue;
                boolean edge = !fp.covers(x + 1, z) || !fp.covers(x - 1, z) || !fp.covers(x, z + 1) || !fp.covers(x, z - 1);
                boolean bay = edge && isBay(v, fp, x, z, bayPeriod);
                for (int s = 0; s < v.storeys(); s++) {
                    int yb = v.y0() + 2 * s;
                    if (edge) {
                        byte st = bay ? Cell.MARK_A : Cell.WALL;
                        write(x, yb, z, st, comp);
                        write(x, yb + 1, z, st, comp);
                    } else {
                        write(x, yb, z, Cell.FLOOR, comp);
                        write(x, yb + 1, z, Cell.AIR, comp);
                    }
                }
                if (roof != Cell.EMPTY) write(x, v.top(), z, roof, comp);
            }
    }

    private void write(int x, int y, int z, byte s, int comp) {
        if (!g.inBounds(x, y, z)) return;
        byte cur = g.get(x, y, z);
        if (cur == s) return;
        // a volume standing on another volume's roof replaces that roof slab with its own floor and walls
        boolean roofBelow = cur == Cell.TERRACE || cur == Cell.ROOF;
        if (cur != Cell.EMPTY && !roofBelow) {
            // occupied by another element (or a reserved void): never overwrite, count the conflict
            conflicts++;
            return;
        }
        g.set(x, y, z, s, comp);
    }

    /** Facade rhythm: piers at corners and every {@code period}-th cell along the edge. */
    static boolean isBay(Massing.Volume v, Footprint fp, int x, int z, int period) {
        boolean ex = !fp.covers(x + 1, z) || !fp.covers(x - 1, z);
        boolean ez = !fp.covers(x, z + 1) || !fp.covers(x, z - 1);
        if (v.shape() == Massing.Shape.BOX) {
            if (ex && ez) return false; // corner
            int t = ex ? z - v.z0() : x - v.x0();
            return Math.floorMod(t, period) != 0;
        }
        if (ex && ez) {
            // staircase corner of a rasterised circle: keep as pier only at cardinal points
            double a = Math.atan2(z + 0.5 - v.cz(), x + 0.5 - v.cx());
            double r = Math.hypot(x + 0.5 - v.cx(), z + 0.5 - v.cz());
            return Math.floorMod((int) Math.floor((a + Math.PI) * r), period) != 0;
        }
        double a = Math.atan2(z + 0.5 - v.cz(), x + 0.5 - v.cx());
        double r = Math.hypot(x + 0.5 - v.cx(), z + 0.5 - v.cz());
        return Math.floorMod((int) Math.floor((a + Math.PI) * r), period) != 0;
    }

    /** A straight stair run rising {@code rise} cells from (x, y, z) toward horizontal direction d (0:+x 1:-x 2:+z 3:-z). */
    public void stairRun(int x, int y, int z, int d, int rise, int comp, boolean solidBelow, int groundY) {
        int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
        byte st = switch (d) {
            case 0 -> Cell.STAIR_XP;
            case 1 -> Cell.STAIR_XN;
            case 2 -> Cell.STAIR_ZP;
            default -> Cell.STAIR_ZN;
        };
        for (int i = 0; i < rise; i++) {
            int cx = x + i * dx, cz = z + i * dz, cy = y + i;
            g.set(cx, cy, cz, st, comp);
            if (g.get(cx, cy + 1, cz) != Cell.EMPTY && Cell.isSolid(g.get(cx, cy + 1, cz))) g.set(cx, cy + 1, cz, Cell.EMPTY, comp);
            if (solidBelow) for (int yy = cy - 1; yy >= groundY; yy--) {
                byte b = g.get(cx, yy, cz);
                // the stair's mass replaces paving it stands on, so no walkable cell ends up under masonry
                if (b == Cell.EMPTY || b == Cell.AIR || b == Cell.PATH || b == Cell.TERRACE || b == Cell.GRASS) g.set(cx, yy, cz, Cell.WALL, comp);
                else break;
            }
        }
    }

    /** A vertical circulation core of size n x n from level y0 (inclusive) to y1 (exclusive). */
    public void core(int x, int z, int n, int y0, int y1, int comp) {
        for (int y = y0; y < y1; y++)
            for (int dz = 0; dz < n; dz++)
                for (int dx = 0; dx < n; dx++) g.set(x + dx, y, z + dz, Cell.CORE, comp);
    }

    /** Cuts a two-cell-tall doorway at (x, y, z). */
    public void door(int x, int y, int z, int comp) {
        g.set(x, y, z, Cell.DOOR, comp);
        byte above = g.get(x, y + 1, z);
        if (Cell.isSolid(above) || Cell.isTemporary(above)) g.set(x, y + 1, z, Cell.AIR, comp);
    }

    /**
     * Lays a bridge deck along a straight segment from (ax, az) to (bx, bz) at level y by stamping
     * width x width blocks at fine intervals (so the deck is always 4-connected). Only exterior air
     * becomes deck; walls and window bays that the deck meets become doorways; building interiors
     * are left untouched. Returns the deck cells written and fills {@code deckOut} (if non-null).
     */
    public int bridge(double ax, double az, double bx, double bz, int y, int width, int comp, java.util.List<int[]> deckOut) {
        double len = Math.hypot(bx - ax, bz - az);
        int n = Math.max(1, (int) Math.ceil(len * 4));
        int written = 0;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double fx = ax + (bx - ax) * t - (width - 1) / 2.0, fz = az + (bz - az) * t - (width - 1) / 2.0;
            int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
            for (int dz = 0; dz < width; dz++)
                for (int dx = 0; dx < width; dx++) {
                    int x = x0 + dx, z = z0 + dz;
                    if (!g.inBounds(x, y, z)) continue;
                    int key = g.index(x, y, z);
                    if (!seen.add(key)) continue;
                    byte cur = g.get(x, y, z);
                    if (cur == Cell.EMPTY) {
                        g.set(x, y, z, Cell.BRIDGE, comp);
                        clearAbove(x, y, z, comp);
                        written++;
                        if (deckOut != null) deckOut.add(new int[]{x, y, z});
                    } else if (cur == Cell.WALL || cur == Cell.MARK_A || cur == Cell.WINDOW) {
                        door(x, y, z, comp);
                    }
                }
        }
        return written;
    }

    private void clearAbove(int x, int y, int z, int comp) {
        byte a = g.get(x, y + 1, z);
        if (a == Cell.VEG || a == Cell.LIGHT) g.set(x, y + 1, z, Cell.EMPTY, comp);
    }

    /** Tapered, noisy rock spire: radius r0 at the bottom, r1 at the top level. */
    public void spire(int cx, int cz, double r0, double r1, int y0, int y1, Rng rng, int comp, double rough) {
        long seed = rng.nextLong();
        for (int y = y0; y < y1; y++) {
            double t = (double) (y - y0) / Math.max(1, (y1 - y0 - 1));
            double r = r0 + (r1 - r0) * Math.pow(t, 0.7);
            int ri = (int) Math.ceil(r + rough + 1);
            for (int z = cz - ri; z <= cz + ri; z++)
                for (int x = cx - ri; x <= cx + ri; x++) {
                    double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                    double a = Math.atan2(dz, dx);
                    double n = rough * (0.55 * Math.sin(a * 3 + seed % 7) + 0.3 * Math.sin(a * 7 + y * 0.35)
                        + 0.4 * (Rng.hash01(seed, (int) (a * 6), y / 3, 0) - 0.5));
                    if (dx * dx + dz * dz <= (r + n) * (r + n)) g.set(x, y, z, Cell.TERRAIN, comp);
                }
        }
    }

    /** Fills every column of the footprint with {@code s} from y0 up to (excluding) y1. */
    public void solid(Footprint fp, int x0, int z0, int x1, int z1, int y0, int y1, byte s, int comp) {
        for (int y = y0; y < y1; y++)
            for (int z = z0; z < z1; z++)
                for (int x = x0; x < x1; x++)
                    if (fp.covers(x, z)) g.set(x, y, z, s, comp);
    }
}
