package studio.forma.engine.arch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The high-level architectural state of a design: volumes (masses with a footprint, base level
 * and height in storeys), links between volumes (bridges, arcades) and protected voids.
 *
 * <p>This is the representation sampled by MCMC refinement: it is small, every proposal move on it
 * has an exact reverse move, and it can be re-realised into a full voxel design deterministically.
 * Instances are immutable; moves return modified copies.
 */
public record Massing(int sx, int sy, int sz, int groundLevel, List<Volume> volumes, List<Link> links, List<Void> voids) {

    public enum Shape { BOX, ROUND, RING }

    /**
     * @param cx,cz       footprint centre in cells (for ROUND/RING the centre lies on a cell corner)
     * @param w,d         footprint size (for ROUND/RING: w = outer diameter, d = inner diameter)
     * @param y0          base level in cells (even = storey aligned)
     * @param storeys     height in storeys (two cells each)
     * @param terraced    roof is a planted, walkable terrace
     * @param courtyard   size of a central void kept open to the sky (0 = none)
     * @param mobile      may MCMC move/resize it
     * @param role        preset-specific role name ("core", "ring", "tower", "platform", ...)
     * @param variant     preset-specific style index (material/roof family)
     */
    public record Volume(String name, String role, Shape shape, int cx, int cz, int w, int d, int y0, int storeys,
                         boolean terraced, int courtyard, boolean mobile, int variant, boolean floating) {
        public int top() {
            return y0 + 2 * storeys;
        }

        public Volume withCenter(int ncx, int ncz) {
            return new Volume(name, role, shape, ncx, ncz, w, d, y0, storeys, terraced, courtyard, mobile, variant, floating);
        }

        public Volume withStoreys(int s) {
            return new Volume(name, role, shape, cx, cz, w, d, y0, s, terraced, courtyard, mobile, variant, floating);
        }

        public Volume withTerraced(boolean t) {
            return new Volume(name, role, shape, cx, cz, w, d, y0, storeys, t, courtyard, mobile, variant, floating);
        }

        public Volume withCourtyard(int c) {
            return new Volume(name, role, shape, cx, cz, w, d, y0, storeys, terraced, c, mobile, variant, floating);
        }

        public Volume withSize(int nw, int nd) {
            return new Volume(name, role, shape, cx, cz, nw, nd, y0, storeys, terraced, courtyard, mobile, variant, floating);
        }

        /** Horizontal footprint bounds [x0, x1) x [z0, z1). */
        public int x0() { return cx - w / 2; }
        public int x1() { return x0() + w; }
        public int z0() { return cz - (shape == Shape.BOX ? d : w) / 2; }
        public int z1() { return z0() + (shape == Shape.BOX ? d : w); }

        /** Whether the cell column (x, z) lies inside the footprint. */
        public boolean covers(int x, int z) {
            if (shape == Shape.BOX) return x >= x0() && x < x1() && z >= z0() && z < z1();
            double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
            double r2 = dx * dx + dz * dz;
            double ro = w / 2.0, ri = d / 2.0;
            if (r2 > ro * ro) return false;
            return shape == Shape.ROUND || r2 >= ri * ri;
        }

        public double footprintArea() {
            if (shape == Shape.BOX) return (double) w * d;
            double ro = w / 2.0, ri = shape == Shape.RING ? d / 2.0 : 0;
            return Math.PI * (ro * ro - ri * ri);
        }

        public double minWidth() {
            if (shape == Shape.BOX) return Math.min(w, d);
            if (shape == Shape.RING) return (w - d) / 2.0;
            return w;
        }
    }

    /** A connection between two volumes at a given level (cells), e.g. a sky bridge. */
    public record Link(int a, int b, int level, String kind, boolean enabled) {
        public Link withEnabled(boolean e) {
            return new Link(a, b, level, kind, e);
        }

        public Link withLevel(int l) {
            return new Link(a, b, level == l ? level : l, kind, enabled);
        }
    }

    /** A protected open space (atrium, courtyard, plaza, canal): no mass may occupy it. */
    public record Void(String name, int cx, int cz, double radius, int y0, int y1) {
        public boolean covers(int x, int z) {
            double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    public Massing {
        volumes = List.copyOf(volumes);
        links = List.copyOf(links);
        voids = List.copyOf(voids);
    }

    public Massing withVolume(int i, Volume v) {
        List<Volume> nv = new ArrayList<>(volumes);
        nv.set(i, v);
        return new Massing(sx, sy, sz, groundLevel, nv, links, voids);
    }

    public Massing withLink(int i, Link l) {
        List<Link> nl = new ArrayList<>(links);
        nl.set(i, l);
        return new Massing(sx, sy, sz, groundLevel, volumes, nl, voids);
    }

    public List<Link> enabledLinks() {
        List<Link> out = new ArrayList<>();
        for (Link l : links) if (l.enabled()) out.add(l);
        return Collections.unmodifiableList(out);
    }

    public int indexOfRole(String role) {
        for (int i = 0; i < volumes.size(); i++) if (volumes.get(i).role().equals(role)) return i;
        return -1;
    }
}
