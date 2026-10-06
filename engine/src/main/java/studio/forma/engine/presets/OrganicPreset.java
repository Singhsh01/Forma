package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.arch.Massing.Link;
import studio.forma.engine.arch.Massing.Shape;
import studio.forma.engine.arch.Massing.Volume;
import studio.forma.engine.core.Cell;
import studio.forma.engine.core.Components.Material;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.Rng;
import studio.forma.engine.mcmc.Objectives;
import studio.forma.engine.mcmc.RefineProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F. Organic Habitat. Living trunks rise from a meadow; each carries seed-shaped pods on branching
 * struts, and walkway tubes join pods of neighbouring trunks at the same level.
 *
 * <p>Curved forms are not meshed from voxels. The voxel grid still holds every pod (floor disk,
 * wall ring, roof) so circulation, occupancy and support are validated on it, but the pod shells,
 * trunks and tubes are drawn as separate curved primitives (ellipsoids, tapered cylinders, rings).
 */
public final class OrganicPreset extends BasePreset {
    static final int G = 2;
    static final int FIRST = 7;      // height of the first pod level above the meadow
    static final int PITCH = 5;      // vertical spacing of pod levels (pod is 4 cells tall plus a gap)
    static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));

    @Override public String id() { return "organic"; }
    @Override public String title() { return "Organic Habitat"; }
    @Override public String summary() {
        return "Seed-shaped pods on living trunks, joined by walkway tubes above a meadow.";
    }

    @Override public List<String> notes() {
        return List.of("Pod shells, trunks and walkway tubes are curved primitives drawn over the voxel design; validation runs on the voxels underneath.",
            "Each pod rests on a branching strut grown diagonally out of its trunk; the support check follows those struts.",
            "Walkways only join pods on the same level, and only along lines that clear every other pod and trunk.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("trunks", "Trunks", "Number of living trunks.", 3, 7, 5, "massing"),
            ParamSpec.integer("levels", "Pod levels", "Pods stacked up each trunk.", 1, 5, 3, "massing"),
            ParamSpec.integer("pod", "Pod radius", "Radius of each pod in cells.", 3, 5, 4, "massing"),
            ParamSpec.real("spread", "Spread", "How far apart the trunks stand.", 0.6, 1.4, 0.05, 1.0, "massing"),
            ParamSpec.real("walkways", "Walkways", "Share of candidate walkway tubes that are built.", 0, 1, 0.05, 0.7, "circulation"),
            ParamSpec.real("meadow", "Meadow", "Wandering footpaths, shrubs and crowns of foliage.", 0, 1, 0.05, 0.6, "detail"),
            ParamSpec.toggle("pond", "Pond", "A pond in the clearing between the trunks.", true, "water"));
    }

    @Override public List<String> programIds() {
        return List.of("growth", "detailing");
    }

    @Override public String defaultProgram(String id, Params p) {
        double meadow = p.get("meadow");
        return switch (id) {
            case "growth" -> String.format(Locale.ROOT, """
                sequence growth
                  one footpaths steps=%d
                    rule wander "pGG" -> "ppG" sym=rotate   # footpaths wander out across the meadow
                  prl reeds steps=1
                    rule reed "wR EG *E" -> "** ** *V" p=0.5 sym=rotate   # reeds take the grass at the pond edge
                """, (int) (20 + 220 * meadow));
            case "detailing" -> String.format(Locale.ROOT, """
                sequence detailing
                  prl shrubs steps=1
                    rule shrub "G E" -> "* V" p=%.3f sym=none
                  prl lanterns steps=1
                    rule walkway "B E" -> "* l" p=0.1 sym=none   # lanterns along the walkway decks
                    rule path "p E" -> "* l" p=0.025 sym=none
                """, 0.008 + 0.04 * meadow);
            default -> throw new IllegalArgumentException("unknown program " + id);
        };
    }

    static int spreadRadius(Params p) {
        return (int) Math.round((6 + 2.6 * p.i("trunks")) * p.get("spread"));
    }

    @Override public int[] gridSize(Params p) {
        int s = 2 * (spreadRadius(p) + p.i("pod") + 6);
        return new int[]{s, G + FIRST + PITCH * p.i("levels") + 10, s};
    }

    @Override public double[] camera(Params p) {
        return new double[]{0, FIRST + PITCH * p.i("levels") / 2.0, 0, 90, -35, 30};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("mist", false, "perspective", 0);
    }

    // ---- composition ------------------------------------------------------------------------

    @Override
    public Massing compose(Params p, Rng rng) {
        int[] size = gridSize(p);
        int sx = size[0], sz = size[2];
        int nT = p.i("trunks"), levels = p.i("levels"), R = p.i("pod");
        int spread = spreadRadius(p);
        List<Volume> vs = new ArrayList<>();
        double turn = rng.nextDouble() * Math.PI * 2;
        // trunks on a sunflower spiral, so they fill the clearing evenly
        int[][] trunk = new int[nT][];
        for (int i = 0; i < nT; i++) {
            double r = spread * Math.sqrt((i + 0.5) / nT);
            double a = turn + i * GOLDEN + (rng.nextDouble() - 0.5) * 0.4;
            trunk[i] = new int[]{sx / 2 + (int) Math.round(r * Math.cos(a)), sz / 2 + (int) Math.round(r * Math.sin(a))};
        }
        List<Volume> pods = new ArrayList<>();
        int[] tops = new int[nT];
        for (int i = 0; i < nT; i++) {
            int count = Math.max(1, levels - (levels > 1 && rng.nextDouble() < 0.35 ? 1 : 0));
            int k0 = levels - count > 0 && rng.nextDouble() < 0.5 ? 1 : 0;
            double phi = rng.nextDouble() * Math.PI * 2;
            int top = G + FIRST;
            for (int k = k0; k < k0 + count; k++) {
                int y0 = G + FIRST + PITCH * k;
                double base = phi + k * GOLDEN;
                for (int attempt = 0; attempt < 8; attempt++) {
                    double a = base + attempt * Math.PI / 4;
                    double dist = R + 1.5;
                    int cx = trunk[i][0] + (int) Math.round(dist * Math.cos(a)), cz = trunk[i][1] + (int) Math.round(dist * Math.sin(a));
                    Volume v = new Volume("Pod " + (pods.size() + 1), "pod", Shape.ROUND, cx, cz, 2 * R, 0, y0, 2,
                        rng.nextDouble() < 0.2 + 0.3 * p.get("meadow"), 0, true, i, false);
                    if (podClash(v, pods, trunk, i, R) == null && inSite(v, sx, sz)) {
                        pods.add(v);
                        top = Math.max(top, y0 + 4);
                        break;
                    }
                }
            }
            tops[i] = top + 2;
        }
        for (int i = 0; i < nT; i++)
            vs.add(new Volume("Trunk " + (i + 1), "trunk", Shape.ROUND, trunk[i][0], trunk[i][1], 2, 0, G, (tops[i] - G + 1) / 2, false, 0, false, i, false));
        vs.addAll(pods);
        // candidate walkways: pods of different trunks on one level, nearest first
        List<Link> links = new ArrayList<>();
        List<double[]> cand = new ArrayList<>();
        for (int a = nT; a < vs.size(); a++)
            for (int b = a + 1; b < vs.size(); b++) {
                Volume va = vs.get(a), vb = vs.get(b);
                if (va.y0() != vb.y0() || va.variant() == vb.variant()) continue;
                double gap = Math.hypot(va.cx() - vb.cx(), va.cz() - vb.cz()) - 2 * R;
                if (gap < 1 || gap > 12) continue;
                cand.add(new double[]{gap, a, b});
            }
        cand.sort((u, w) -> Double.compare(u[0], w[0]));
        int[] degree = new int[vs.size()];
        Massing probe = new Massing(sx, size[1], sz, G, vs, List.of(), List.of());
        for (double[] c : cand) {
            int a = (int) c[1], b = (int) c[2];
            if (degree[a] >= 2 || degree[b] >= 2) continue;
            if (!walkwayClear(probe, a, b)) continue;
            boolean on = rng.nextDouble() < p.get("walkways");
            links.add(new Link(a, b, vs.get(a).y0(), "walkway", on));
            degree[a]++;
            degree[b]++;
        }
        return new Massing(sx, size[1], sz, G, vs, links, List.of());
    }

    static boolean inSite(Volume v, int sx, int sz) {
        int R = v.w() / 2;
        return v.cx() - R >= 2 && v.cz() - R >= 2 && v.cx() + R <= sx - 2 && v.cz() + R <= sz - 2;
    }

    /** Why pod v cannot stand where it is, or null: it overlaps another pod, another trunk, or drifts off its own trunk. */
    static String podClash(Volume v, List<Volume> pods, int[][] trunk, int own, int R) {
        double dOwn = Math.hypot(v.cx() - trunk[own][0], v.cz() - trunk[own][1]);
        if (dOwn < R + 1 || dOwn > R + 3.5) return v.name() + " is not seated on its trunk";
        for (int t = 0; t < trunk.length; t++)
            if (t != own && Math.hypot(v.cx() - trunk[t][0], v.cz() - trunk[t][1]) < R + 2.5) return v.name() + " touches trunk " + (t + 1);
        for (Volume o : pods) {
            if (o == v || o.name().equals(v.name())) continue;
            if (Math.abs(o.y0() - v.y0()) >= 5) continue;
            if (Math.hypot(o.cx() - v.cx(), o.cz() - v.cz()) < o.w() / 2.0 + v.w() / 2.0 + 1) return v.name() + " overlaps " + o.name();
        }
        // the strut below a pod needs the space under it on its own trunk side
        for (Volume o : pods) {
            if (o == v || o.name().equals(v.name()) || o.variant() != v.variant()) continue;
            if (o.y0() == v.y0() - PITCH && Math.hypot(o.cx() - v.cx(), o.cz() - v.cz()) < 2 * R - 1) return v.name() + " sits on " + o.name();
        }
        return null;
    }

    /** A straight walkway between pods a and b must keep clear of every other pod on that level and of every trunk. */
    static boolean walkwayClear(Massing m, int a, int b) {
        Volume va = m.volumes().get(a), vb = m.volumes().get(b);
        for (int i = 0; i < m.volumes().size(); i++) {
            if (i == a || i == b) continue;
            Volume o = m.volumes().get(i);
            double clear;
            if (o.role().equals("trunk")) clear = 2.5;
            else if (Math.abs(o.y0() - va.y0()) < 5) clear = o.w() / 2.0 + 1.5;
            else continue;
            if (segmentDistance(va.cx(), va.cz(), vb.cx(), vb.cz(), o.cx(), o.cz()) < clear) return false;
        }
        return true;
    }

    static double segmentDistance(double ax, double az, double bx, double bz, double px, double pz) {
        double vx = bx - ax, vz = bz - az;
        double t = ((px - ax) * vx + (pz - az) * vz) / Math.max(1e-9, vx * vx + vz * vz);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(ax + t * vx - px, az + t * vz - pz);
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        int R = p.i("pod");
        ctx.expectStages(7);
        List<Integer> trunks = new ArrayList<>(), pods = new ArrayList<>();
        for (int i = 0; i < m.volumes().size(); i++) (m.volumes().get(i).role().equals("trunk") ? trunks : pods).add(i);
        int[] compOf = new int[m.volumes().size()];
        int[] path = new int[1];

        ctx.stage("site", "Meadow and pond", "constructive",
            "Lay the meadow, dig the pond in the widest clearing and pave a ring around every trunk foot.", () -> {
                int earth = ctx.comps.add("Ground", "terrain", Material.ROCK);
                int meadow = ctx.comps.add("Meadow", "garden", Material.ROCK, false, false);
                path[0] = ctx.comps.add("Footpaths", "path", Material.SANDSTONE, true, false);
                int water = ctx.comps.add("Pond", "water", Material.ROCK);
                for (int z = 0; z < g.sz; z++)
                    for (int x = 0; x < g.sx; x++) {
                        for (int y = 0; y < G; y++) g.set(x, y, z, Cell.TERRAIN, earth);
                        g.set(x, G, z, Cell.GRASS, meadow);
                    }
                if (p.b("pond")) {
                    // the pond goes where it is farthest from every trunk
                    int bx = g.sx / 2, bz = g.sz / 2;
                    double best = -1;
                    for (int z = 6; z < g.sz - 6; z += 2)
                        for (int x = 6; x < g.sx - 6; x += 2) {
                            double d = 1e9;
                            for (int t : trunks) d = Math.min(d, Math.hypot(x - m.volumes().get(t).cx(), z - m.volumes().get(t).cz()));
                            for (int q : pods) d = Math.min(d, Math.hypot(x - m.volumes().get(q).cx(), z - m.volumes().get(q).cz()) - R + 1);
                            double centre = Math.hypot(x - g.sx / 2.0, z - g.sz / 2.0) * 0.45;
                            if (d - centre > best) {
                                best = d - centre;
                                bx = x;
                                bz = z;
                            }
                        }
                    double pr = Math.max(3.5, Math.min(7, best - 1.5));
                    int cells = 0;
                    for (int z = 0; z < g.sz; z++)
                        for (int x = 0; x < g.sx; x++) {
                            double wob = 0.7 * Math.sin(Math.atan2(z - bz, x - bx) * 3 + bx);
                            if (Math.hypot(x + 0.5 - bx, z + 0.5 - bz) <= pr + wob) {
                                g.set(x, G, z, Cell.EMPTY, 0);
                                g.set(x, G - 1, z, Cell.WATER, water);
                                cells++;
                            }
                        }
                    ctx.op("pond of " + cells + " cells dug in the widest clearing");
                }
                for (int t : trunks) {
                    Volume v = m.volumes().get(t);
                    for (int z = v.cz() - 3; z < v.cz() + 3; z++)
                        for (int x = v.cx() - 3; x < v.cx() + 3; x++)
                            if (g.inBounds(x, G, z) && g.get(x, G, z) == Cell.GRASS) g.set(x, G, z, Cell.PATH, path[0]);
                }
                // a footpath from the south edge links every trunk foot (nearest-neighbour chain of L-shaped paths)
                int ex = g.sx / 2, ez = g.sz - 1;
                List<Integer> left = new ArrayList<>(trunks);
                int cx = ex, cz = ez;
                while (!left.isEmpty()) {
                    int bi = 0;
                    double bd = 1e9;
                    for (int k = 0; k < left.size(); k++) {
                        Volume v = m.volumes().get(left.get(k));
                        double d = Math.abs(v.cx() - cx) + Math.abs(v.cz() - cz);
                        if (d < bd) {
                            bd = d;
                            bi = k;
                        }
                    }
                    Volume v = m.volumes().get(left.remove(bi));
                    footpath(g, cx, cz, v.cx(), v.cz(), path[0]);
                    cx = v.cx();
                    cz = v.cz();
                }
                ctx.entrance(ex, G, ez);
                ctx.op("meadow laid, trunk feet paved and joined by a footpath from the south edge");
            });

        ctx.stage("masses", "Trunks and pods", "constructive",
            "Raise each trunk as a circulation core, grow a branching strut out of it and seat a pod on the strut.", () -> {
                int struts = 0;
                for (int t : trunks) {
                    Volume v = m.volumes().get(t);
                    compOf[t] = ctx.comps.add(v.name(), "trunk", Material.TIMBER, true, false);
                    ctx.primOnly.add(compOf[t]);
                    ctx.kit.core(v.cx() - 1, v.cz() - 1, 2, G, v.top(), compOf[t]);
                    // the trunk is drawn as a tapered cylinder with a flared root
                    ctx.prim("tube", "trunk", v.cx(), (G + v.top()) / 2.0, v.cz(), 3.0, v.top() - G, 3.0, 0, 0, compOf[t]);
                    ctx.prim("cone", "trunk", v.cx(), G + 1.4, v.cz(), 5.4, 2.8, 5.4, 0, 0, compOf[t]);
                }
                for (int q : pods) {
                    Volume v = m.volumes().get(q);
                    Volume tr = m.volumes().get(trunks.get(v.variant()));
                    compOf[q] = ctx.comps.add(v.name(), "pod", Material.PLASTER, true, false);
                    ctx.primOnly.add(compOf[q]);
                    struts += strut(ctx, tr, v, compOf[trunks.get(v.variant())]);
                    pod(ctx, v, compOf[q]);
                }
                ctx.op(trunks.size() + " trunks, " + pods.size() + " pods, " + struts + " strut cells grown diagonally out of the trunks");
            });

        ctx.stage("circulation", "Branches and walkways", "constructive",
            "Each pod opens onto its trunk core through a short branch; walkway tubes join pods of neighbouring trunks.", () -> {
                int built = 0;
                for (int q : pods) {
                    Volume v = m.volumes().get(q);
                    Volume tr = m.volumes().get(trunks.get(v.variant()));
                    ctx.kit.bridge(tr.cx() - 0.5, tr.cz() - 0.5, v.cx() - 0.5, v.cz() - 0.5, v.y0(), 2, compOf[q], null);
                }
                int walk = ctx.comps.add("Walkways", "bridge", Material.TIMBER, true, false);
                for (Link l : m.links()) {
                    if (!l.enabled()) continue;
                    Volume a = m.volumes().get(l.a()), b = m.volumes().get(l.b());
                    ctx.kit.bridge(a.cx() - 0.5, a.cz() - 0.5, b.cx() - 0.5, b.cz() - 0.5, a.y0(), 2, walk, null);
                    tube(ctx, a, b, walk, R);
                    built++;
                }
                ctx.op(pods.size() + " branches from core to pod; " + built + " of " + m.links().size() + " candidate walkway tubes built");
            });

        ctx.stage("growth", "Rule-driven growth", "rules",
            "Rules wander footpaths across the meadow and plant reeds at the pond edge.",
            () -> ctx.runProgram("growth", defaultProgram("growth", p)));

        ctx.stage("validation", "Constraint checks", "validation",
            "Walk from the meadow edge up every trunk core and along every branch; follow load paths through the struts.", () -> {
                // a pod is a shell: its floor disk and wall ring are checked as a cantilever of up to 9 cells from the strut head
                standardValidation(ctx, true, 9, 9, 16);
                int bad = 0;
                List<String> why = new ArrayList<>();
                for (int q : pods) {
                    Volume v = m.volumes().get(q);
                    List<Volume> others = new ArrayList<>();
                    for (int o : pods) if (o != q) others.add(m.volumes().get(o));
                    int[][] tr = trunkArray(m, trunks);
                    String c = podClash(v, others, tr, v.variant(), R);
                    if (c != null) {
                        bad++;
                        if (why.size() < 2) why.add(c);
                    }
                }
                ctx.report.add("pods", "Pods seated and clear of each other", "hard",
                    bad == 0 ? studio.forma.engine.arch.ConstraintReport.Status.PASS : studio.forma.engine.arch.ConstraintReport.Status.FAIL,
                    bad == 0 ? "every pod sits on its own trunk with clearance to every other pod and trunk" : String.join("; ", why), bad);
            });

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Shrubs on the meadow, lanterns on walkways and paths, crowns of foliage on the trunks.", () -> {
                ctx.runProgram("detailing", defaultProgram("detailing", p));
                Rng r = ctx.rng.fork("crowns");
                double meadow = p.get("meadow");
                for (int t : trunks) {
                    Volume v = m.volumes().get(t);
                    int n = 3 + (int) Math.round(4 * meadow);
                    for (int k = 0; k < n; k++) {
                        double a = k * GOLDEN * 2 + r.nextDouble();
                        double rr = k == 0 ? 0 : 1.2 + r.nextDouble() * 1.8;
                        double s = 3.2 + r.nextDouble() * 2.2 - (k == 0 ? -1 : 0);
                        ctx.prim("foliage", k % 3 == 1 ? "foliage-alt" : "foliage", v.cx() + rr * Math.cos(a), v.top() + 0.8 + r.nextDouble() * 1.4 - rr * 0.3,
                            v.cz() + rr * Math.sin(a), s, s * 0.8, s, a, 0, compOf[t]);
                    }
                }
                for (int q : pods) {
                    Volume v = m.volumes().get(q);
                    if (!v.terraced()) continue;
                    for (int k = 0; k < 3; k++) {
                        double a = k * 2.1 + r.nextDouble();
                        double s = 1.2 + r.nextDouble() * 0.8;
                        ctx.prim("foliage", k == 1 ? "foliage-bloom" : "foliage-alt", v.cx() + 1.1 * Math.cos(a), v.y0() + 4.6, v.cz() + 1.1 * Math.sin(a), s, s * 0.7, s, a, 0, compOf[q]);
                    }
                }
                var conn = studio.forma.engine.arch.Validator.connectivity(g, ctx.comps, ctx.entranceIndices());
                ctx.op(String.format("re-check after detailing: %d of %d walkable cells reachable", conn.reachable(), conn.standable()));
            });
    }

    static int[][] trunkArray(Massing m, List<Integer> trunks) {
        int[][] t = new int[trunks.size()][];
        for (int i = 0; i < trunks.size(); i++) {
            Volume v = m.volumes().get(trunks.get(i));
            t[i] = new int[]{v.cx(), v.cz()};
        }
        return t;
    }

    static void footpath(Grid g, int x0, int z0, int x1, int z1, int comp) {
        int x = x0, z = z0;
        while (x != x1 || z != z1) {
            for (int dz = 0; dz < 2; dz++) {
                int cz = z + dz;
                if (g.inBounds(x, G, cz) && g.get(x, G, cz) == Cell.GRASS) g.set(x, G, cz, Cell.PATH, comp);
                else if (g.inBounds(x, G, cz) && g.get(x, G, cz) == Cell.EMPTY && g.get(x, G - 1, cz) == Cell.WATER) g.set(x, G, cz, Cell.BRIDGE, comp);
            }
            if (z != z1) z += Integer.signum(z1 - z);
            else x += Integer.signum(x1 - x);
        }
    }

    /** Diagonal branching strut from the trunk core out under the pod's floor. Returns cells written. */
    static int strut(GenContext ctx, Volume tr, Volume pod, int comp) {
        Grid g = ctx.grid;
        double ang = Math.atan2(pod.cz() - tr.cz(), pod.cx() - tr.cx());
        int d = Math.abs(Math.cos(ang)) >= Math.abs(Math.sin(ang)) ? (Math.cos(ang) > 0 ? 0 : 1) : (Math.sin(ang) > 0 ? 2 : 3);
        int written = 0;
        double sx0 = 0, sz0 = 0, sy0 = 0;
        int chainSteps = 0;
        for (int side = -1; side <= 1; side += 2) {
            // two parallel struts, one cell apart, leave the core face toward the pod
            // the core occupies cells cx-1..cx, cz-1..cz; start on the cell just outside its face
            int x = tr.cx() + (d == 0 ? 1 : d == 1 ? -2 : (side < 0 ? -1 : 0));
            int z = tr.cz() + (d == 2 ? 1 : d == 3 ? -2 : (side < 0 ? -1 : 0));
            int dist = (int) Math.round(Math.hypot(pod.cx() - tr.cx(), pod.cz() - tr.cz()) - 1.5);
            int steps = Math.max(2, Math.min(4, dist));
            int y = pod.y0() - steps;
            if (side < 0) {
                sx0 = x + 0.5 + (d < 2 ? 0 : 0.5);
                sz0 = z + 0.5 + (d < 2 ? 0.5 : 0);
                sy0 = y;
                chainSteps = steps;
            }
            for (int s = 0; s < steps; s++) {
                if (!g.inBounds(x, y, z)) break;
                if (!ctx.kit.putIfOpen(x, y, z, Cell.TRUNK, comp)) break;
                written++;
                x += AX[d];
                z += AZ[d];
                y++;
            }
        }
        // drawn as one stout limb between the two strut chains, from the core face up under the floor
        double len = Math.sqrt(2) * chainSteps;
        double mx = sx0 + AX[d] * (chainSteps - 1) / 2.0, mz = sz0 + AZ[d] * (chainSteps - 1) / 2.0, my = sy0 + chainSteps / 2.0;
        ctx.prim("tube", "trunk", mx, my, mz, 1.15, len + 0.6, 1.15, Math.atan2(AX[d], AZ[d]), Math.PI / 4, comp);
        return written;
    }

    /** Pod voxels (floor disk, wall ring, roof) plus its ellipsoid shell, band and porthole windows. */
    static void pod(GenContext ctx, Volume v, int comp) {
        Grid g = ctx.grid;
        int R = v.w() / 2, y0 = v.y0();
        for (int z = v.cz() - R - 1; z <= v.cz() + R; z++)
            for (int x = v.cx() - R - 1; x <= v.cx() + R; x++) {
                double r = Math.hypot(x + 0.5 - v.cx(), z + 0.5 - v.cz());
                if (r > R) continue;
                boolean ring = r > R - 1.45;   // thick enough that the rasterised ring is 4-connected
                if (ring) for (int y = y0; y < y0 + 3; y++) ctx.kit.putIfOpen(x, y, z, Cell.WALL, comp);
                else {
                    ctx.kit.putIfOpen(x, y0, z, Cell.FLOOR, comp);
                    ctx.kit.putIfOpen(x, y0 + 1, z, Cell.AIR, comp);
                    ctx.kit.putIfOpen(x, y0 + 2, z, Cell.AIR, comp);
                }
                if (r <= R - 0.5) ctx.kit.putIfOpen(x, y0 + 3, z, Cell.ROOF, comp);
            }
        double a = R + 0.45;
        ctx.prim("pod", "shell", v.cx(), y0 + 1.6, v.cz(), 2 * a, 6.6, 2 * a, 0, 0, comp);
        ctx.prim("torus", "copper", v.cx(), y0 + 0.35, v.cz(), 2 * a * 0.86, 3, 2 * a * 0.86, 0, 0, comp);
        int n = Math.max(6, (int) Math.round(2 * Math.PI * a / 1.6));
        double phase = Rng.hash01(v.cx(), v.cz(), y0, 3) * Math.PI;
        for (int k = 0; k < n; k++) {
            double ang = phase + k * 2 * Math.PI / n;
            double rr = a * 0.985;
            ctx.prim("sphere", (k % 3 == 0) ? "glass" : "glass-lit", v.cx() + rr * Math.cos(ang), y0 + 1.75, v.cz() + rr * Math.sin(ang),
                0.22, 0.95, 0.75, -ang, 0, comp);
        }
        // a skylight eye on the crown
        ctx.prim("sphere", "glass-lit", v.cx(), y0 + 4.86, v.cz(), 1.2, 0.16, 1.2, 0, 0, comp);
    }

    /** Glass walkway tube with ribs between two pods; only the stretch outside the pod shells is drawn. */
    static void tube(GenContext ctx, Volume a, Volume b, int comp, int R) {
        double dx = b.cx() - a.cx(), dz = b.cz() - a.cz();
        double len = Math.hypot(dx, dz);
        double ux = dx / len, uz = dz / len;
        double start = R + 0.2, end = len - R - 0.2;
        if (end <= start) return;
        double mx = a.cx() - 0.5 + ux * (start + end) / 2 + 0.5, mz = a.cz() - 0.5 + uz * (start + end) / 2 + 0.5;
        double yaw = Math.atan2(ux, uz);
        double y = a.y0() + 1.05;
        ctx.prim("tube", "glass", mx, y, mz, 2.5, end - start, 2.5, yaw, Math.PI / 2, comp);
        int ribs = Math.max(2, (int) Math.round((end - start) / 1.4));
        for (int k = 0; k <= ribs; k++) {
            double t = start + (end - start) * k / ribs;
            ctx.prim("torus", "copper", a.cx() + ux * t, y, a.cz() + uz * t, 2.6, 2.0, 2.6, yaw, Math.PI / 2, comp);
        }
    }

    // ---- refinement -------------------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        List<Integer> trunks = new ArrayList<>();
        for (int i = 0; i < probe.volumes().size(); i++) {
            if (probe.volumes().get(i).role().equals("pod")) {
                rp.movable.add(i);
                rp.terraceToggle.add(i);
            } else trunks.add(i);
        }
        for (int i = 0; i < probe.links().size(); i++) rp.linkToggle.add(i);
        int R = p.i("pod");
        rp.objective("circulation", "Linked pods", "Pods joined by walkways, with short tubes.",
            m -> {
                double pen = 0;
                for (Link l : m.links()) {
                    Volume a = m.volumes().get(l.a()), b = m.volumes().get(l.b());
                    double gap = Math.hypot(a.cx() - b.cx(), a.cz() - b.cz()) - 2 * R;
                    pen += l.enabled() ? gap * 0.05 : 0.6;
                }
                return pen;
            });
        rp.objective("daylight", "Daylight between pods", "Pods do not sit directly over a pod two levels down.",
            m -> {
                double pen = 0;
                for (Volume a : m.volumes())
                    for (Volume b : m.volumes())
                        if (a.role().equals("pod") && b.role().equals("pod") && a.y0() > b.y0())
                            pen += Math.max(0, 2 * R - Math.hypot(a.cx() - b.cx(), a.cz() - b.cz())) * 0.1;
                return pen;
            });
        rp.objective("balance", "Balanced trunks", "Pods spread around their trunk instead of all leaning one way.",
            m -> {
                double pen = 0;
                for (int t : trunks) {
                    Volume tr = m.volumes().get(t);
                    double sx = 0, sz = 0;
                    int n = 0;
                    for (Volume v : m.volumes())
                        if (v.role().equals("pod") && v.variant() == tr.variant()) {
                            sx += v.cx() - tr.cx();
                            sz += v.cz() - tr.cz();
                            n++;
                        }
                    if (n > 1) pen += Math.hypot(sx, sz) / n * 0.25;
                }
                return pen;
            });
        rp.objective("greenery", "Crown gardens", "About a third of the pods carry a garden on their crown.",
            m -> Objectives.greenery(m, Objectives.role("pod"), 0.33));
        rp.hard("site bounds", m -> Objectives.withinGrid(m, 2));
        rp.hard("pods seated and clear", m -> {
            int[][] tr = trunkArray(m, trunks);
            List<Volume> podsList = new ArrayList<>();
            for (Volume v : m.volumes()) if (v.role().equals("pod")) podsList.add(v);
            for (Volume v : podsList) {
                String c = podClash(v, podsList, tr, v.variant(), R);
                if (c != null) return c;
            }
            return null;
        });
        rp.hard("walkways clear", m -> {
            for (Link l : m.links())
                if (l.enabled() && !walkwayClear(m, l.a(), l.b())) return "a walkway would pass through a pod or trunk";
            return null;
        });
        return rp;
    }
}
