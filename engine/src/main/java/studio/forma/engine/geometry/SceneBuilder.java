package studio.forma.engine.geometry;

import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;

import java.util.BitSet;

/**
 * Turns a semantic cell grid into renderable instanced geometry.
 *
 * <p>This is where generation and presentation part ways: a FLOOR cell becomes a thin slab at the
 * bottom of its cell, a WINDOW becomes an inset lit pane with sill and lintel, walls are merged
 * into large boxes (greedy meshing), columns and piers merge vertically, terrace and bridge edges
 * get parapets or railings where there is a drop, vegetation becomes jittered foliage.
 */
public final class SceneBuilder {
    private final Grid g;
    private final Components comps;
    private final Scene scene;
    private final long seed;
    private final double ox, oz;

    public SceneBuilder(Grid g, Components comps, long seed) {
        this.g = g;
        this.comps = comps;
        this.seed = seed;
        this.ox = -g.sx / 2.0;
        this.oz = -g.sz / 2.0;
        this.scene = new Scene(ox, oz);
    }

    private java.util.Set<Integer> primOnly = java.util.Set.of();

    public static Scene build(Grid g, Components comps, long seed) {
        return new SceneBuilder(g, comps, seed).run();
    }

    /** Builds the scene, adding preset primitives and skipping cell walls of primitive-drawn components. */
    public static Scene build(Grid g, Components comps, long seed, java.util.List<studio.forma.engine.GenContext.Prim> prims,
                              java.util.Set<Integer> primOnly) {
        SceneBuilder b = new SceneBuilder(g, comps, seed);
        b.primOnly = primOnly;
        Scene s = b.run();
        for (var p : prims) {
            double var = Rng.hash01(seed, (int) (p.cx() * 7), (int) (p.cy() * 7), (int) (p.cz() * 7));
            s.group(p.kind(), p.material()).add(b.ox + p.cx(), p.cy(), b.oz + p.cz(), p.sx(), p.sy(), p.sz(), p.rot(), p.tilt(), var, p.comp());
        }
        return s;
    }

    private String mat(int i) {
        return comps.get(g.compIndex(i)).material().name().toLowerCase();
    }

    private Scene run() {
        BitSet done = new BitSet(g.size());
        // massive cells: greedy 3D merge per (category, component)
        mergeBoxes(done, s -> s == Cell.WALL || s == Cell.MARK_A || s == Cell.MARK_B || s == Cell.MARK_C || s == Cell.MARK_D, "wall");
        mergeBoxes(done, s -> s == Cell.TERRAIN, "rock");
        mergeBoxes(done, s -> s == Cell.ROOF, "roof");
        mergeBoxes(done, s -> s == Cell.CORE, "core");
        // slabs: 2D merge per layer
        mergeSlabs(done, Cell.FLOOR, "floor", 0.18);
        mergeSlabs(done, Cell.DOOR, "floor", 0.18);
        mergeSlabs(done, Cell.SHELF, "floor", 0.18);
        mergeSlabs(done, Cell.TERRACE, "paving", 0.26);
        mergeSlabs(done, Cell.PATH, "paving", 0.2);
        mergeSlabs(done, Cell.GRASS, "grass", 0.36);
        mergeSlabs(done, Cell.BRIDGE, "deck", 0.3);
        mergeSlabs(done, Cell.CANOPY, "canopy", 0.16);
        mergeSlabs(done, Cell.WATER, "water", 0.78);
        mergeSlabs(done, Cell.GLASS, "glass", 0.12);
        // vertical runs
        mergeVertical(Cell.COLUMN, "cyl", 0.22, "column");
        mergeVertical(Cell.SUPPORT, "box", 0.46, "pier");
        mergeVertical(Cell.TRUNK, "cyl", 0.12, "trunk");
        branchConnectors();
        windows();
        cells();
        edges();
        return scene;
    }

    interface StatePred {
        boolean test(byte s);
    }

    private void mergeBoxes(BitSet done, StatePred want, String category) {
        int sx = g.sx, sy = g.sy, sz = g.sz;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    int i = g.index(x, y, z);
                    if (done.get(i) || !want.test(g.getIndex(i))) continue;
                    if (skipped(i)) { done.set(i); continue; }
                    short c = g.compIndex(i);
                    int x1 = x + 1;
                    while (x1 < sx && ok(done, want, x1, y, z, c)) x1++;
                    int z1 = z + 1;
                    outerZ:
                    while (z1 < sz) {
                        for (int xx = x; xx < x1; xx++) if (!ok(done, want, xx, y, z1, c)) break outerZ;
                        z1++;
                    }
                    int y1 = y + 1;
                    outerY:
                    while (y1 < sy) {
                        for (int zz = z; zz < z1; zz++)
                            for (int xx = x; xx < x1; xx++) if (!ok(done, want, xx, y1, zz, c)) break outerY;
                        y1++;
                    }
                    for (int yy = y; yy < y1; yy++)
                        for (int zz = z; zz < z1; zz++)
                            for (int xx = x; xx < x1; xx++) done.set(g.index(xx, yy, zz));
                    String material = switch (category) {
                        case "rock" -> comps.get(c).material() == Components.Material.ROCK ? "rock" : mat(i);
                        case "roof" -> comps.get(c).kind().equals("terrain") ? "rock" : comps.roof(c).name().toLowerCase();
                        case "core" -> mat(i);
                        default -> mat(i);
                    };
                    String kind = category.equals("rock") ? "box" : "box";
                    double var = Rng.hash01(seed, x, y, z);
                    scene.group(kind, material).add(ox + (x + x1) / 2.0, (y + y1) / 2.0, oz + (z + z1) / 2.0,
                        x1 - x, y1 - y, z1 - z, 0, var, c);
                }
    }

    private boolean ok(BitSet done, StatePred want, int x, int y, int z, short c) {
        int i = g.index(x, y, z);
        return !done.get(i) && want.test(g.getIndex(i)) && g.compIndex(i) == c;
    }

    private boolean skipped(int i) {
        return !primOnly.isEmpty() && primOnly.contains((int) g.compIndex(i));
    }

    private void mergeSlabs(BitSet done, byte state, String material, double thickness) {
        int sx = g.sx, sz = g.sz;
        for (int y = 0; y < g.sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    int i = g.index(x, y, z);
                    if (done.get(i) || g.getIndex(i) != state) continue;
                    short c = g.compIndex(i);
                    int x1 = x + 1;
                    while (x1 < sx && slabOk(done, state, x1, y, z, c)) x1++;
                    int z1 = z + 1;
                    outer:
                    while (z1 < sz) {
                        for (int xx = x; xx < x1; xx++) if (!slabOk(done, state, xx, y, z1, c)) break outer;
                        z1++;
                    }
                    for (int zz = z; zz < z1; zz++) for (int xx = x; xx < x1; xx++) done.set(g.index(xx, y, zz));
                    String m = material.equals("glass") && comps.litGlass(c) ? "glass-lit" : material;
                    scene.group("box", m).add(ox + (x + x1) / 2.0, y + thickness / 2.0, oz + (z + z1) / 2.0,
                        x1 - x, thickness, z1 - z, 0, Rng.hash01(seed, x, y, z), c);
                }
    }

    private boolean slabOk(BitSet done, byte state, int x, int y, int z, short c) {
        int i = g.index(x, y, z);
        return !done.get(i) && g.getIndex(i) == state && g.compIndex(i) == c;
    }

    private void mergeVertical(byte state, String kind, double radius, String material) {
        for (int z = 0; z < g.sz; z++)
            for (int x = 0; x < g.sx; x++) {
                int y = 0;
                while (y < g.sy) {
                    if (g.get(x, y, z) != state) { y++; continue; }
                    short c = g.comp(x, y, z);
                    int y1 = y + 1;
                    while (y1 < g.sy && g.get(x, y1, z) == state && g.comp(x, y1, z) == c) y1++;
                    String m = material;
                    if (material.equals("column") || material.equals("pier")) {
                        String cm = comps.get(c).material().name().toLowerCase();
                        m = cm.equals("timber") ? "timber" : (material.equals("pier") ? "pier" : cm);
                    }
                    double d = radius * 2;
                    scene.group(kind, m).add(ox + x + 0.5, (y + y1) / 2.0, oz + z + 0.5, d, y1 - y, d, 0, Rng.hash01(seed, x, y, z), c);
                    y = y1;
                }
            }
    }

    /** Diagonal TRUNK chains (branching columns) are joined by tilted cylinders between cell centres. */
    private void branchConnectors() {
        for (int y = 1; y < g.sy; y++)
            for (int z = 0; z < g.sz; z++)
                for (int x = 0; x < g.sx; x++) {
                    if (g.get(x, y, z) != Cell.TRUNK) continue;
                    for (int d = 0; d < 4; d++) {
                        int bx = x - HX[d], bz = z - HZ[d];
                        byte b = g.get(bx, y - 1, bz);
                        if (b != Cell.TRUNK && b != Cell.COLUMN) continue;
                        if (g.get(x, y - 1, z) == Cell.TRUNK) continue; // vertical trunk, already drawn
                        double yaw = Math.atan2(HX[d], HZ[d]);
                        short c = g.comp(x, y, z);
                        String m = comps.get(c).kind().equals("garden") || b == Cell.TRUNK && comps.get(c).material() == Components.Material.ROCK ? "trunk" : "column";
                        if (m.equals("column")) m = comps.get(c).material().name().toLowerCase();
                        scene.group("cyl", m).add(ox + bx + 0.5 + HX[d] * 0.5, y, oz + bz + 0.5 + HZ[d] * 0.5, 0.26, Math.sqrt(2) * 1.05, 0.26,
                            yaw, Math.PI / 4, 0, c);
                    }
                }
    }

    private static final int[] HX = {1, -1, 0, 0};
    private static final int[] HZ = {0, 0, 1, -1};

    private static boolean interior(byte s) {
        return s == Cell.AIR || s == Cell.FLOOR || s == Cell.SHELF || s == Cell.CORE || s == Cell.DOOR;
    }

    private static boolean outside(byte s) {
        return s == Cell.EMPTY || s == Cell.KEEP || s == Cell.TERRACE || s == Cell.GRASS || s == Cell.BRIDGE
            || s == Cell.PATH || s == Cell.VEG || s == Cell.LIGHT || s == Cell.WATER || Cell.isStair(s) || s == Cell.TRUNK;
    }

    private void windows() {
        for (int z = 0; z < g.sz; z++)
            for (int x = 0; x < g.sx; x++) {
                int y = 0;
                while (y < g.sy) {
                    if (g.get(x, y, z) != Cell.WINDOW || skipped(g.index(x, y, z))) { y++; continue; }
                    int y1 = y + 1;
                    while (y1 < g.sy && g.get(x, y1, z) == Cell.WINDOW && y1 - y < 4) y1++;
                    int n = -1;
                    for (int d = 0; d < 4; d++) {
                        byte o = g.get(x + HX[d], y, z + HZ[d]);
                        byte in = g.get(x - HX[d], y, z - HZ[d]);
                        if (outside(o) && (interior(in) || in == Cell.COLUMN)) { n = d; break; }
                    }
                    if (n < 0)
                        for (int d = 0; d < 4; d++)
                            if (outside(g.get(x + HX[d], y, z + HZ[d]))) { n = d; break; }
                    int i = g.index(x, y, z);
                    short c = g.compIndex(i);
                    String wm = mat(i);
                    double cx = ox + x + 0.5, cz = oz + z + 0.5, h = y1 - y;
                    if (n < 0) {
                        scene.group("box", wm).add(cx, (y + y1) / 2.0, cz, 1, h, 1, 0, 0, c);
                        y = y1;
                        continue;
                    }
                    double nx = HX[n], nz = HZ[n];
                    boolean alongZ = n < 2; // facade plane spans z
                    double paneH = h - 0.34;
                    double var = Rng.hash01(seed, x, y, z);
                    // pane, inset toward the outside face, framed by masonry jambs
                    scene.group("pane", "window").add(cx + nx * 0.22, y + 0.14 + paneH / 2, cz + nz * 0.22,
                        alongZ ? 0.08 : 0.66, paneH, alongZ ? 0.66 : 0.08, 0, var, c);
                    for (int side = -1; side <= 1; side += 2) {
                        double jx = alongZ ? 0 : side * 0.42, jz = alongZ ? side * 0.42 : 0;
                        scene.group("box", wm).add(cx + jx, y + h / 2, cz + jz, alongZ ? 1 : 0.16, h, alongZ ? 0.16 : 1, 0, 0, c);
                    }
                    // sill and lintel in the wall material
                    scene.group("box", wm).add(cx, y + 0.07, cz, 1, 0.14, 1, 0, 0, c);
                    scene.group("box", wm).add(cx, y1 - 0.1, cz, 1, 0.2, 1, 0, 0, c);
                    // slim mullion frame at the back of the reveal
                    scene.group("box", "frame").add(cx - nx * 0.3, y + h / 2, cz - nz * 0.3,
                        alongZ ? 0.06 : 1, h, alongZ ? 1 : 0.06, 0, 0, c);
                    y = y1;
                }
            }
    }

    private void cells() {
        for (int y = 0; y < g.sy; y++)
            for (int z = 0; z < g.sz; z++)
                for (int x = 0; x < g.sx; x++) {
                    int i = g.index(x, y, z);
                    byte s = g.getIndex(i);
                    short c = g.compIndex(i);
                    double cx = ox + x + 0.5, cz = oz + z + 0.5;
                    double var = Rng.hash01(seed, x, y, z);
                    switch (s) {
                        case Cell.STAIR_XP, Cell.STAIR_XN, Cell.STAIR_ZP, Cell.STAIR_ZN -> {
                            double rot = switch (s) {
                                case Cell.STAIR_XP -> 0;
                                case Cell.STAIR_XN -> Math.PI;
                                case Cell.STAIR_ZP -> -Math.PI / 2;
                                default -> Math.PI / 2;
                            };
                            String m = comps.get(c).kind().equals("terrain") ? "rock" : mat(i);
                            scene.group("stair", m).add(cx, y + 0.5, cz, 1, 1, 1, rot, var, c);
                        }
                        case Cell.VEG -> {
                            boolean tree = g.get(x, y - 1, z) == Cell.TRUNK;
                            double jx = (Rng.hash01(seed, x, y, z + 7) - 0.5) * 0.3, jz = (Rng.hash01(seed, x + 3, y, z) - 0.5) * 0.3;
                            double sc = tree ? 1.5 + 0.8 * var : 0.75 + 0.45 * var;
                            double cy = tree ? y + 0.45 : y + 0.05 + sc * 0.32;
                            String m = var < 0.33 ? "foliage" : var < 0.72 ? "foliage-alt" : "foliage-bloom";
                            if (tree) m = var < 0.5 ? "foliage" : "foliage-alt";
                            scene.group("foliage", m).add(cx + jx, cy, cz + jz, sc, sc * (tree ? 0.95 : 0.8), sc, var * 6.283, var, c);
                        }
                        case Cell.LIGHT -> {
                            byte below = g.get(x, y - 1, z);
                            boolean onGround = Cell.isStandable(below) || below == Cell.TERRACE;
                            if (onGround) scene.group("cyl", "iron").add(cx, y + 0.45, cz, 0.07, 0.9, 0.07, 0, 0, c);
                            scene.group("lamp", "lamp").add(cx, onGround ? y + 0.95 : y + 0.4, cz, 0.3, 0.3, 0.3, 0, var, c);
                        }
                        case Cell.SHELF -> {
                            for (int d = 0; d < 4; d++) {
                                byte nb = g.get(x + HX[d], y, z + HZ[d]);
                                if (nb == Cell.WALL || nb == Cell.MARK_A) {
                                    boolean alongZ = d < 2;
                                    scene.group("box", "books").add(cx + HX[d] * 0.33, y + 0.18 + 0.75, cz + HZ[d] * 0.33,
                                        alongZ ? 0.32 : 0.92, 1.5, alongZ ? 0.92 : 0.32, 0, var, c);
                                    break;
                                }
                            }
                        }
                        case Cell.ARCH -> scene.group("arch", mat(i)).add(cx, y + 0.5, cz, 1, 1, 1, archRot(x, y, z), var, c);
                        default -> {}
                    }
                }
    }

    private double archRot(int x, int y, int z) {
        boolean openX = Cell.isOpen(g.get(x + 1, y, z)) || Cell.isOpen(g.get(x - 1, y, z));
        return openX ? 0 : Math.PI / 2;
    }

    /** Parapets and railings along walkable edges with a drop beside them. */
    private void edges() {
        for (int y = 1; y < g.sy - 1; y++)
            for (int z = 0; z < g.sz; z++)
                for (int x = 0; x < g.sx; x++) {
                    byte s = g.get(x, y, z);
                    boolean terrace = s == Cell.TERRACE || s == Cell.GRASS;
                    boolean bridge = s == Cell.BRIDGE;
                    boolean gallery = s == Cell.FLOOR || s == Cell.PATH;
                    if (!terrace && !bridge && !gallery) continue;
                    short c = g.comp(x, y, z);
                    for (int d = 0; d < 4; d++) {
                        int nx = x + HX[d], nz = z + HZ[d];
                        byte n = g.get(nx, y, nz);
                        if (!(n == Cell.EMPTY || n == Cell.KEEP)) continue;
                        byte below = g.get(nx, y - 1, nz);
                        boolean drop = below == Cell.EMPTY || below == Cell.KEEP || below == Cell.AIR || below == Cell.WATER;
                        if (!drop) continue;
                        if (gallery && s == Cell.FLOOR && n != Cell.KEEP) continue; // interior floors only rail at the atrium
                        if (gallery && s == Cell.PATH) {
                            // plaza edge above a cliff: low stone parapet
                            if (below != Cell.EMPTY) continue;
                        }
                        boolean alongZ = d < 2;
                        double ex = ox + x + 0.5 + HX[d] * 0.46, ez = oz + z + 0.5 + HZ[d] * 0.46;
                        if (bridge || (gallery && s == Cell.FLOOR)) {
                            scene.group("box", "brass").add(ex, y + 0.95, ez, alongZ ? 0.06 : 1, 0.06, alongZ ? 1 : 0.06, 0, 0, c);
                            scene.group("box", "brass").add(ex, y + 0.6, ez, alongZ ? 0.03 : 1, 0.03, alongZ ? 1 : 0.03, 0, 0, c);
                            if (((x + z) & 1) == 0)
                                scene.group("box", "brass").add(ex, y + 0.62, ez, 0.05, 0.66, 0.05, 0, 0, c);
                        } else {
                            String m = s == Cell.PATH ? "rock" : mat(g.index(x, y, z));
                            if (m.equals("timber")) m = "sandstone";
                            scene.group("box", m).add(ex, y + 0.26 + 0.28, ez, alongZ ? 0.16 : 1, 0.56, alongZ ? 1 : 0.16, 0, 0, c);
                        }
                    }
                }
    }
}
