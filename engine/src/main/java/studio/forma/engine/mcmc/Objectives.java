package studio.forma.engine.mcmc;

import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.Massing.Volume;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reusable penalty terms and hard checks on {@link Massing} states. Penalties are >= 0 with 0 as
 * the preferred value. They encode stated design preferences, not truths about beauty.
 */
public final class Objectives {
    private Objectives() {}

    static List<Volume> withRole(Massing m, Predicate<Volume> p) {
        List<Volume> out = new ArrayList<>();
        for (Volume v : m.volumes()) if (p.test(v)) out.add(v);
        return out;
    }

    public static Predicate<Volume> role(String r) {
        return v -> v.role().equals(r);
    }

    /** Peak silhouette: one dominant volume; others should be lower, ideally at {@code ratio} of its height, and fall off with distance. */
    public static double peakSilhouette(Massing m, int peakIndex, String role, double ratio) {
        Volume peak = m.volumes().get(peakIndex);
        double ph = peak.top() - peak.y0();
        List<Volume> others = withRole(m, role(role));
        if (others.isEmpty()) return 0;
        double pen = 0;
        for (Volume v : others) {
            double h = v.top() - v.y0();
            double r = h / ph;
            if (r >= 0.97) pen += 4 * (r - 0.97 + 0.05);
            pen += (r - ratio) * (r - ratio) * 3;
        }
        // inversions: a volume farther from the peak should not be taller than a nearer one
        int inv = 0, pairs = 0;
        for (Volume a : others)
            for (Volume b : others) {
                if (a == b) continue;
                double da = Math.hypot(a.cx() - peak.cx(), a.cz() - peak.cz());
                double db = Math.hypot(b.cx() - peak.cx(), b.cz() - peak.cz());
                if (da + 2 < db) {
                    pairs++;
                    if (b.top() > a.top()) inv++;
                }
            }
        return pen / others.size() + (pairs == 0 ? 0 : 0.6 * inv / (double) pairs);
    }

    /** Daylight proxy: penalise volumes whose footprints come within {@code minGap} cells of each other, weighted by the taller one. */
    public static double crowding(Massing m, Predicate<Volume> filter, double minGap) {
        List<Volume> vs = withRole(m, filter);
        double pen = 0;
        for (int i = 0; i < vs.size(); i++)
            for (int j = i + 1; j < vs.size(); j++) {
                double gap = gap(vs.get(i), vs.get(j));
                if (gap < minGap) {
                    double h = Math.max(vs.get(i).storeys(), vs.get(j).storeys());
                    pen += (minGap - gap) / minGap * (0.5 + h / 20.0);
                }
            }
        return pen;
    }

    /** Approximate clear distance between two footprints (circle/box approximations). */
    public static double gap(Volume a, Volume b) {
        double d = Math.hypot(a.cx() - b.cx(), a.cz() - b.cz());
        return d - radius(a) - radius(b);
    }

    public static double radius(Volume v) {
        if (v.shape() == Massing.Shape.BOX) return Math.max(v.w(), v.d()) / 2.0 * 0.85;
        return v.w() / 2.0;
    }

    /** Proportion: height (cells) over minimum width should be near {@code target}. */
    public static double slenderness(Massing m, Predicate<Volume> filter, double target) {
        List<Volume> vs = withRole(m, filter);
        if (vs.isEmpty()) return 0;
        double pen = 0;
        for (Volume v : vs) {
            double s = (v.storeys() * 2.0) / Math.max(1, v.minWidth());
            double e = (s - target) / target;
            pen += e * e;
        }
        return pen / vs.size();
    }

    /** Controlled variety: coefficient of variation of heights near {@code targetCv}. */
    public static double variety(Massing m, Predicate<Volume> filter, double targetCv) {
        List<Volume> vs = withRole(m, filter);
        if (vs.size() < 2) return 0;
        double mean = 0;
        for (Volume v : vs) mean += v.storeys();
        mean /= vs.size();
        double var = 0;
        for (Volume v : vs) var += (v.storeys() - mean) * (v.storeys() - mean);
        double cv = Math.sqrt(var / vs.size()) / Math.max(1e-9, mean);
        return Math.abs(cv - targetCv) * 4;
    }

    /** Greenery: fraction of eligible volumes with roof gardens near {@code target}. */
    public static double greenery(Massing m, Predicate<Volume> filter, double target) {
        List<Volume> vs = withRole(m, filter);
        if (vs.isEmpty()) return 0;
        int t = 0;
        for (Volume v : vs) if (v.terraced()) t++;
        return Math.abs((double) t / vs.size() - target) * 2;
    }

    /** Circulation: volumes of a role without any enabled link, plus a small cost per unit bridge length. */
    public static double linkage(Massing m, Predicate<Volume> filter, double lengthCost) {
        double pen = 0;
        for (int i = 0; i < m.volumes().size(); i++) {
            Volume v = m.volumes().get(i);
            if (!filter.test(v)) continue;
            boolean linked = false;
            for (Massing.Link l : m.links()) if (l.enabled() && (l.a() == i || l.b() == i)) linked = true;
            if (!linked) pen += 1;
        }
        for (Massing.Link l : m.links()) {
            if (!l.enabled() || l.a() < 0 || l.b() < 0) continue;
            Volume a = m.volumes().get(l.a()), b = m.volumes().get(l.b());
            pen += lengthCost * Math.max(0, gap(a, b));
        }
        return pen;
    }

    /** Balance: horizontal centroid of volume mass (footprint x height) close to the site centre. */
    public static double balance(Massing m, Predicate<Volume> filter) {
        double sx = 0, sz = 0, w = 0;
        for (Volume v : withRole(m, filter)) {
            double mass = v.footprintArea() * v.storeys();
            sx += v.cx() * mass;
            sz += v.cz() * mass;
            w += mass;
        }
        if (w == 0) return 0;
        double dx = sx / w - m.sx() / 2.0, dz = sz / w - m.sz() / 2.0;
        return Math.hypot(dx, dz) / 4.0;
    }

    /** Open court: the courtyard of a volume should be large relative to its height (daylight into the void). */
    public static double courtLight(Massing m, int index, double targetRatio) {
        Volume v = m.volumes().get(index);
        double ratio = (2.0 * v.courtyard()) / Math.max(1, v.storeys() * 2.0);
        return ratio >= targetRatio ? 0 : (targetRatio - ratio) / targetRatio * 2;
    }

    /** Density: total footprint area relative to the site area near {@code target}. */
    public static double density(Massing m, Predicate<Volume> filter, double siteArea, double target) {
        double a = 0;
        for (Volume v : withRole(m, filter)) a += v.footprintArea();
        return Math.abs(a / siteArea - target) * 3;
    }

    // ---- hard checks -------------------------------------------------------------------------

    public static String withinGrid(Massing m, int margin) {
        for (Volume v : m.volumes()) {
            if (v.x0() < margin || v.z0() < margin || v.x1() > m.sx() - margin || v.z1() > m.sz() - margin)
                return v.name() + " leaves the site";
            if (v.top() + 2 > m.sy()) return v.name() + " is taller than the site allows";
        }
        return null;
    }

    /** No two volumes (of the filtered kinds) may come closer than {@code minGap} cells. */
    public static String separated(Massing m, Predicate<Volume> a, Predicate<Volume> b, double minGap) {
        List<Volume> vs = m.volumes();
        for (int i = 0; i < vs.size(); i++)
            for (int j = i + 1; j < vs.size(); j++) {
                Volume p = vs.get(i), q = vs.get(j);
                boolean pair = (a.test(p) && b.test(q)) || (a.test(q) && b.test(p));
                if (!pair) continue;
                if (gap(p, q) < minGap) return p.name() + " collides with " + q.name();
            }
        return null;
    }

    /** Ring-shaped footprint test: distance from a centre must exceed {@code r}. */
    public static String outsideRadius(Massing m, Predicate<Volume> filter, int cx, int cz, double r) {
        for (Volume v : withRole(m, filter)) {
            double d = Math.hypot(v.cx() - cx, v.cz() - cz) - radius(v);
            if (d < r) return v.name() + " intrudes on the terraces";
        }
        return null;
    }

    public static String linksWithin(Massing m, double maxLength) {
        for (Massing.Link l : m.links()) {
            if (!l.enabled() || l.a() < 0 || l.b() < 0) continue;
            Volume a = m.volumes().get(l.a()), b = m.volumes().get(l.b());
            if (gap(a, b) > maxLength) return "a bridge would span more than " + (int) maxLength + " cells";
            if (l.level() + 2 > a.top() || l.level() + 2 > b.top()) return "a bridge would land above a roof";
            if (l.level() < a.y0() || l.level() < b.y0()) return "a bridge would land below a building";
        }
        return null;
    }
}
