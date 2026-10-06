package studio.forma.engine.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Final renderable geometry: instanced primitives grouped by (kind, material).
 *
 * <p>Each instance is 9 floats: centre x, y, z; size x, y, z; rotation about y (radians);
 * tilt about the rotated x axis (radians, applied after the y rotation); variation in [0, 1)
 * (used for tint/brightness jitter). World units are grid cells
 * (1 cell = 1.5 m); x east, y up, z south; the origin is the bottom centre of the grid.
 * Kinds: box, cyl, stair, foliage, pane, lamp, cone, arch, sphere.
 */
public final class Scene {
    public static final int STRIDE = 9;

    public static final class Group {
        public final String kind, material;
        float[] data = new float[STRIDE * 64];
        int[] comp = new int[64];
        int count;

        Group(String kind, String material) {
            this.kind = kind;
            this.material = material;
        }

        public void add(double cx, double cy, double cz, double sx, double sy, double sz, double rot, double var, int component) {
            add(cx, cy, cz, sx, sy, sz, rot, 0, var, component);
        }

        public void add(double cx, double cy, double cz, double sx, double sy, double sz, double rot, double tilt, double var, int component) {
            if (count == comp.length) {
                comp = Arrays.copyOf(comp, count * 2);
                data = Arrays.copyOf(data, count * 2 * STRIDE);
            }
            int o = count * STRIDE;
            data[o] = (float) cx; data[o + 1] = (float) cy; data[o + 2] = (float) cz;
            data[o + 3] = (float) sx; data[o + 4] = (float) sy; data[o + 5] = (float) sz;
            data[o + 6] = (float) rot; data[o + 7] = (float) tilt; data[o + 8] = (float) var;
            comp[count] = component;
            count++;
        }

        public int count() { return count; }
        public float[] data() { return Arrays.copyOf(data, count * STRIDE); }
        public int[] comps() { return Arrays.copyOf(comp, count); }
    }

    private final Map<String, Group> groups = new LinkedHashMap<>();
    public final double offsetX, offsetZ;

    public Scene(double offsetX, double offsetZ) {
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    public Group group(String kind, String material) {
        return groups.computeIfAbsent(kind + "|" + material, k -> new Group(kind, material));
    }

    public List<Group> groups() {
        List<Group> out = new ArrayList<>();
        for (Group g : groups.values()) if (g.count > 0) out.add(g);
        return out;
    }

    public int instanceCount() {
        int n = 0;
        for (Group g : groups.values()) n += g.count;
        return n;
    }

    /** Axis-aligned bounds of all instances: minX, minY, minZ, maxX, maxY, maxZ. */
    public double[] bounds() {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Group g : groups.values())
            for (int i = 0; i < g.count; i++) {
                int o = i * STRIDE;
                for (int a = 0; a < 3; a++) {
                    double half = g.data[o + 3 + a] / 2.0;
                    b[a] = Math.min(b[a], g.data[o + a] - half);
                    b[a + 3] = Math.max(b[a + 3], g.data[o + a] + half);
                }
            }
        if (b[0] == Double.MAX_VALUE) return new double[6];
        return b;
    }
}
