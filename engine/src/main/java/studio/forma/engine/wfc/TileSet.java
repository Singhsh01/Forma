package studio.forma.engine.wfc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A set of tiles with face sockets for tile-based Wave Function Collapse.
 *
 * <p>Faces are indexed {@code 0:+x 1:-x 2:+y 3:-y 4:+z 5:-z}. Two tiles may be neighbours along a
 * direction when the socket on the touching faces is identical. Tiles can be expanded into their
 * quarter-turn rotations about y; duplicate rotations are removed. At most 63 tiles are allowed
 * so that a cell's domain fits in one {@code long}.
 */
public final class TileSet {
    public static final int PX = 0, NX = 1, PY = 2, NY = 3, PZ = 4, NZ = 5;
    public static final int[] DX = {1, -1, 0, 0, 0, 0};
    public static final int[] DY = {0, 0, 1, -1, 0, 0};
    public static final int[] DZ = {0, 0, 0, 0, 1, -1};

    public static int opposite(int d) {
        return d ^ 1;
    }

    public record Tile(String name, String base, int rotation, String[] sockets, double weight) {}

    private final List<Tile> tiles = new ArrayList<>();
    private long[][] compatible; // [direction][tile] -> mask of allowed neighbour tiles

    public TileSet add(String name, double weight, String px, String nx, String py, String ny, String pz, String nz, boolean rotate) {
        String[] s = {px, nx, py, ny, pz, nz};
        List<String[]> seen = new ArrayList<>();
        for (int r = 0; r < (rotate ? 4 : 1); r++) {
            boolean dup = false;
            for (String[] o : seen) if (java.util.Arrays.equals(o, s)) dup = true;
            if (!dup) {
                seen.add(s);
                tiles.add(new Tile(rotate ? name + "@" + r : name, name, r, s, weight));
            }
            s = rotateY(s);
        }
        if (tiles.size() > 63) throw new IllegalStateException("too many tiles (max 63)");
        compatible = null;
        return this;
    }

    /** Quarter turn about y, mapping +x -> +z -> -x -> -z -> +x (matches Pattern.rotatedY). */
    static String[] rotateY(String[] s) {
        // after rotating the tile, the face that pointed +x now points +z, etc.
        String[] r = new String[6];
        r[PZ] = s[PX];
        r[NX] = s[PZ];
        r[NZ] = s[NX];
        r[PX] = s[NZ];
        r[PY] = s[PY];
        r[NY] = s[NY];
        return r;
    }

    public int size() {
        return tiles.size();
    }

    public Tile tile(int i) {
        return tiles.get(i);
    }

    public List<Tile> tiles() {
        return Collections.unmodifiableList(tiles);
    }

    public int indexOf(String name) {
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).name().equals(name)) return i;
        return -1;
    }

    /** Mask of all rotations of a base tile. */
    public long maskOfBase(String base) {
        long m = 0;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).base().equals(base)) m |= 1L << i;
        return m;
    }

    /** Mask of tiles whose socket on face {@code d} equals {@code socket}. */
    public long maskWithSocket(int d, String socket) {
        long m = 0;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).sockets()[d].equals(socket)) m |= 1L << i;
        return m;
    }

    public long allMask() {
        return tiles.size() == 64 ? -1L : (1L << tiles.size()) - 1;
    }

    long[][] compatible() {
        if (compatible == null) {
            int n = tiles.size();
            compatible = new long[6][n];
            for (int d = 0; d < 6; d++)
                for (int a = 0; a < n; a++)
                    for (int b = 0; b < n; b++)
                        if (tiles.get(a).sockets()[d].equals(tiles.get(b).sockets()[opposite(d)]))
                            compatible[d][a] |= 1L << b;
        }
        return compatible;
    }
}
