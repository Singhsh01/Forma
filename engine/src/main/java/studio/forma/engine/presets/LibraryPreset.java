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
 * A. The Library Above the Clouds (flagship).
 *
 * <p>A round atrium tower of reading galleries rises from a rock pinnacle. Concentric reading
 * rooms step down around it as planted terraces; satellite towers stand on outcrops and are tied
 * to the atrium tower by sky bridges; platforms hang at cloud level. Clouds are presentation only.
 * Grounded mode carries every platform on piers; fantasy mode lets reading islands float and
 * labels them as floating.
 */
public final class LibraryPreset extends BasePreset {
    static final int G = 18;          // plaza level (top of the pinnacle)
    static final int RING_WIDTH = 4;  // cells per terrace ring
    static final int MAX_BRIDGE = 30; // longest buildable bridge span (cells)

    @Override public String id() { return "library"; }
    @Override public String title() { return "The Library Above the Clouds"; }
    @Override public String summary() {
        return "Terraced reading rooms ring a tall open atrium; linked towers, sky bridges and roof gardens float above a sea of cloud.";
    }

    @Override public List<String> notes() {
        return List.of(
            "Clouds are a rendering effect; the generator builds a rock pinnacle and treats cloud height as presentation.",
            "Grounded mode carries platforms and bridges on piers and checks a simple support heuristic.",
            "Fantasy mode adds floating reading islands; they are flagged as floating, not as supported.");
    }

    @Override public List<ParamSpec> params() {
        return List.of(
            ParamSpec.integer("height", "Atrium tower height", "Storeys of the central reading tower.", 8, 22, 16, "massing"),
            ParamSpec.integer("terraces", "Terrace rings", "Rings of reading rooms stepping down around the atrium.", 1, 4, 3, "massing"),
            ParamSpec.integer("atrium", "Atrium radius", "Radius of the open, sky-lit atrium (cells).", 2, 6, 3, "massing"),
            ParamSpec.integer("towers", "Satellite towers", "Towers standing on outcrops around the library.", 0, 6, 4, "massing"),
            ParamSpec.real("bridges", "Bridge frequency", "Share of candidate sky-bridge levels that are built.", 0, 1, 0.05, 0.6, "circulation"),
            ParamSpec.real("symmetry", "Symmetry", "1 keeps towers evenly spaced and equal; lower values loosen the composition.", 0, 1, 0.05, 0.8, "massing"),
            ParamSpec.real("vegetation", "Vegetation", "Density of terrace gardens, trees and shrubs.", 0, 1, 0.05, 0.6, "detail"),
            ParamSpec.real("variation", "Facade variation", "How freely windows, piers and ribbons vary.", 0, 1, 0.05, 0.4, "detail"),
            ParamSpec.integer("platforms", "Cloud platforms", "Reading platforms at cloud level.", 0, 4, 2, "massing"),
            ParamSpec.toggle("fantasy", "Fantasy mode", "Platforms become floating islands tethered by bridges.", false, "mode"));
    }

    @Override public List<String> programIds() {
        return List.of("growth", "detailing", "refine");
    }

    @Override public String defaultProgram(String id, Params p) {
        double var = p.get("variation"), veg = p.get("vegetation");
        return switch (id) {
            case "growth" -> String.format(Locale.ROOT, """
                sequence growth
                  prl party-walls steps=1
                    rule party "i1#" -> "*W*" sym=rotate      # a bay pressed against another mass closes up
                    rule twin "i11i" -> "*WW*" sym=rotate     # two bays facing each other across a party wall
                  prl tall-windows steps=1
                    rule tall "1 1" -> "N N" p=%.2f sym=none  # stacked bay cells become tall lit reading-room windows
                  prl band-windows steps=1
                    rule band "1" -> "N" p=%.2f sym=none      # some leftover bays keep a single window
                  prl blank-bays steps=1
                    rule blank "1" -> "W" sym=none            # the rest close as solid wall
                  prl garden-seeds steps=1
                    rule seed "T E" -> "G E" p=%.3f sym=none  # seed garden plots on open terraces
                  one gardens steps=%d
                    rule grow "GT" -> "GG"                    # Eden growth: gardens spread across terraces
                """, 0.94 - 0.3 * var, 0.35 + 0.3 * var, 0.006 + 0.03 * veg, (int) (60 + 1400 * veg));
            case "detailing" -> String.format(Locale.ROOT, """
                sequence detailing
                  prl bookshelves steps=1
                    rule shelf "FW" -> "SW" p=0.85 sym=rotate     # bookshelves line solid interior walls
                  prl trees steps=1
                    rule tree "G E E" -> "* t V" p=%.3f sym=none  # small trees root in garden soil
                  prl shrubs steps=1
                    rule shrub "G E" -> "* V" p=%.3f sym=none     # shrubs and flower beds
                  prl lanterns steps=1
                    rule lantern "T E" -> "* l" p=0.035 sym=none  # lanterns along open terraces
                    rule bridge-lamp "BEB" -> "*l*" p=0.0 sym=rotate
                """, 0.02 + 0.10 * veg, 0.05 + 0.25 * veg).replace("    rule bridge-lamp \"BEB\" -> \"*l*\" p=0.0 sym=rotate\n", "");
            case "refine" -> String.format(Locale.ROOT, """
                sequence refine
                  prl ribbon-windows steps=1
                    rule merge "iii/NWN/ooo" -> "***/*N*/***" p=%.2f sym=full   # merge a pier between two windows into a ribbon
                  prl clear-doors steps=1
                    rule clear "DG AV" -> "** *E" sym=rotate                  # keep doorways free of shrubs
                """, Math.max(0.01, 0.45 * var));
            default -> throw new IllegalArgumentException("unknown program " + id);
        };
    }

    @Override public int[] gridSize(Params p) {
        int sy = Math.max(64, G + 2 * p.i("height") + 14);
        int rout = p.i("atrium") + 5 + RING_WIDTH * p.i("terraces");
        int reach = p.b("fantasy") && p.i("platforms") > 0 ? rout + 25 : rout + 19;
        int sx = Math.max(76, 2 * reach);
        sx += sx % 2;
        return new int[]{sx, sy, sx};
    }

    @Override public double[] camera(Params p) {
        double h = G + p.i("height") * 1.1;
        return new double[]{0, h * 0.8, 0, 105, -32, 24};
    }

    @Override public Atmosphere atmosphere(Params p) {
        return new Atmosphere("dusk", true, "perspective", G - 5);
    }

    // ---- composition ------------------------------------------------------------------------

    @Override
    public Massing compose(Params p, Rng rng) {
        int[] size = gridSize(p);
        int c = size[0] / 2;
        int H = p.i("height"), T = p.i("terraces"), a = p.i("atrium"), n = p.i("towers");
        double sym = p.get("symmetry");
        boolean fantasy = p.b("fantasy");
        int rc = a + 5;
        List<Volume> vs = new ArrayList<>();
        vs.add(new Volume("Atrium Tower", "core", Shape.RING, c, c, 2 * rc, 2 * a, G, H, true, a, false, 0, false));
        int prevR = rc;
        int[] ringH = new int[T + 1];
        ringH[0] = H;
        for (int k = 1; k <= T; k++) {
            int h = (int) Math.round(H * (0.5 - 0.13 * (k - 1)));
            h = Math.max(1, Math.min(h, ringH[k - 1] - 1));
            ringH[k] = h;
            int ro = prevR + RING_WIDTH;
            vs.add(new Volume("Reading Ring " + k, "ring", Shape.RING, c, c, 2 * ro, 2 * prevR, G, h, true, 0, false, k, false));
            prevR = ro;
        }
        int rout = prevR;
        double base = rng.nextDouble() * Math.PI * 2;
        int tStorey = (int) Math.round(H * 0.72);
        int ring1Top = T >= 1 ? G + 2 * ringH[1] : G;
        List<Link> links = new ArrayList<>();
        List<Integer> towerIdx = new ArrayList<>();
        double maxJitter = Math.min(0.9, 2 * Math.PI / Math.max(1, n) * 0.35);
        for (int i = 0; i < n; i++) {
            double jitter = (1 - sym);
            double th = base + 2 * Math.PI * i / n + (rng.nextDouble() - 0.5) * maxJitter * jitter;
            double r = rout + 8 + rng.nextDouble() * 4 * jitter;
            int cx = c + (int) Math.round(r * Math.cos(th)), cz = c + (int) Math.round(r * Math.sin(th));
            int st = tStorey + (int) Math.round((rng.nextDouble() - 0.5) * 6 * jitter) + (i % 2 == 0 ? 0 : -(int) Math.round(2 * jitter));
            st = Math.max(Math.max(3, ringH.length > 1 ? ringH[1] + 2 : 3), Math.min(H - 1, st));
            boolean garden = (i % 2 == 1) || rng.chance(0.25 * jitter);
            towerIdx.add(vs.size());
            vs.add(new Volume("Tower " + (char) ('A' + i), "tower", Shape.ROUND, cx, cz, 8, 0, G, st, garden, 0, true, i % 3, false));
        }
        // sky bridge candidates: up to three storey-aligned levels per tower above the first ring
        double bf = p.get("bridges");
        for (int ti : towerIdx) {
            Volume t = vs.get(ti);
            int lo = ring1Top + 2, hi = Math.min(t.top(), G + 2 * H) - 2;
            List<Integer> levels = new ArrayList<>();
            for (int y = lo + ((lo - G) % 2 == 0 ? 0 : 1); y <= hi; y += 2) levels.add(y);
            if (levels.isEmpty()) continue;
            int count = Math.min(3, levels.size());
            boolean any = false;
            for (int k = 0; k < count; k++) {
                int y = levels.get((int) Math.round((levels.size() - 1) * (count == 1 ? 0.5 : (double) k / (count - 1))));
                boolean en = rng.nextDouble() < bf;
                any |= en;
                links.add(new Link(0, ti, y, "sky bridge", en));
            }
            if (!any && bf >= 0.15) {
                Link l = links.get(links.size() - count);
                links.set(links.size() - count, l.withEnabled(true));
            }
        }
        // necklace bridges between neighbouring towers (one level each)
        for (int i = 0; i < towerIdx.size() && towerIdx.size() >= 3; i++) {
            int ai = towerIdx.get(i), bi = towerIdx.get((i + 1) % towerIdx.size());
            Volume va = vs.get(ai), vb = vs.get(bi);
            int y = Math.min(va.top(), vb.top()) - 4;
            if ((y - G) % 2 != 0) y--;
            if (y < ring1Top + 2) continue;
            boolean en = rng.nextDouble() < bf * 0.45;
            if (Objectives.gap(va, vb) > MAX_BRIDGE) continue; // too long to build
            links.add(new Link(ai, bi, y, "tower bridge", en));
        }
        // cloud platforms / floating islands
        int np = p.i("platforms");
        for (int i = 0; i < np; i++) {
            double th0 = base + Math.PI / Math.max(1, n) + 2 * Math.PI * i / np + 0.21;
            double r = fantasy ? rout + 16 + (i % 2) * 3 : rout + 11;   // grounded: within the reach the refinement constraint allows
            int y0 = fantasy ? G + 4 + 2 * (i % 3) : G - 2 - 2 * (i % 2);
            Volume placed = null;
            // nudge around the ring until the platform is clear of towers and other platforms
            for (int k = 0; k < 18 && placed == null; k++) {
                double th = th0 + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * 0.17;
                int cx = c + (int) Math.round(r * Math.cos(th)), cz = c + (int) Math.round(r * Math.sin(th));
                Volume cand = new Volume((fantasy ? "Floating Island " : "Cloud Platform ") + (i + 1), "platform", Shape.BOX,
                    cx, cz, 8, 8, y0, 1, false, 0, true, i, fantasy);
                boolean clear = cand.x0() >= 3 && cand.z0() >= 3 && cand.x1() <= size[0] - 3 && cand.z1() <= size[2] - 3;
                for (Volume o : vs) if (clear && (o.role().equals("tower") || o.role().equals("platform")) && Objectives.gap(cand, o) < 3) clear = false;
                if (clear) placed = cand;
            }
            if (placed == null) continue;
            int pi = vs.size();
            vs.add(placed);
            int cx = placed.cx(), cz = placed.cz();
            if (fantasy && !towerIdx.isEmpty()) {
                // tether to the nearest tower that has a floor at the island's level
                int best = -1;
                double bd = 1e9;
                for (int ti : towerIdx) {
                    Volume t = vs.get(ti);
                    if (t.top() <= y0 + 2) continue;
                    double d = Math.hypot(t.cx() - cx, t.cz() - cz);
                    if (d < bd) { bd = d; best = ti; }
                }
                if (best >= 0 && Objectives.gap(vs.get(best), placed) <= MAX_BRIDGE) links.add(new Link(best, pi, y0, "tether bridge", true));
            }
        }
        List<Massing.Void> voids = List.of(new Massing.Void("Atrium", c, c, a, G + 1, G + 2 * H + 1));
        return new Massing(size[0], size[1], size[2], G, vs, links, voids);
    }

    // ---- realisation ------------------------------------------------------------------------

    @Override
    public void realize(Massing m, GenContext ctx) {
        Grid g = ctx.grid;
        Params p = ctx.params;
        boolean fantasy = p.b("fantasy");
        int c = m.sx() / 2;
        Volume core = m.volumes().get(0);
        int a = core.courtyard();
        int rout = core.w() / 2;
        for (Volume v : m.volumes()) if (v.role().equals("ring")) rout = Math.max(rout, v.w() / 2);
        final int routF = rout;
        int[] compOf = new int[m.volumes().size()];
        ctx.expectStages(8);

        ctx.stage("site", "Site and composition", "constructive",
            "Raise the rock pinnacle above the cloud line, reserve the atrium void and pave the plaza.", () -> {
                int site = ctx.comps.add("Pinnacle", "terrain", Material.ROCK);
                ctx.history.setFrameBudget(4500); // the rock is bulk; replay it in a few large frames
                Rng r = ctx.rng.fork("site");
                ctx.kit.spire(c, c, routF + 8.5, routF + 5.2, 0, G, r, site, 1.4);
                ctx.op("rock pinnacle radius " + (routF + 2) + " rising to level " + G);
                for (Volume v : m.volumes())
                    if (v.role().equals("tower")) {
                        ctx.kit.spire(v.cx(), v.cz(), 7.5, 5.6, 0, G, r, site, 1.2);
                        ctx.op("outcrop under " + v.name());
                    }
                // atrium: pool + paving at the bottom, reserved void above, open to the sky
                for (int z = c - a - 1; z <= c + a + 1; z++)
                    for (int x = c - a - 1; x <= c + a + 1; x++) {
                        double dx = x + 0.5 - c, dz = z + 0.5 - c, d = Math.hypot(dx, dz);
                        if (d > a) continue;
                        g.set(x, G, z, d < a - 1.2 ? Cell.WATER : Cell.PATH, site);
                        for (int y = G + 1; y <= core.top(); y++) g.set(x, y, z, Cell.KEEP, site);
                    }
                ctx.op("reserve atrium void of radius " + a + " from level " + (G + 1) + " to the sky");
            });

        ctx.stage("masses", "Major masses", "constructive",
            "Rasterise the atrium tower, the stepped reading rings, the satellite towers and platforms as storeyed shells.", () -> {
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    Material mat = switch (v.role()) {
                        case "core" -> Material.SANDSTONE;
                        case "ring" -> v.variant() == 2 ? Material.TERRACOTTA : v.variant() % 2 == 1 ? Material.PLASTER : Material.SANDSTONE;
                        case "tower" -> v.variant() == 1 ? Material.TERRACOTTA : v.variant() == 2 ? Material.PLASTER : Material.SANDSTONE;
                        default -> Material.TIMBER;
                    };
                    boolean required = !v.role().equals("platform") || !v.floating();
                    compOf[i] = ctx.comps.add(v.name(), v.role(), mat, true, v.floating());
                    if (v.role().equals("platform")) continue;
                    byte roof = v.terraced() || v.role().equals("ring") || v.role().equals("core") ? Cell.TERRACE : Cell.ROOF;
                    ctx.kit.shell(v, v::covers, compOf[i], roof, v.role().equals("tower") ? 3 : 3);
                    ctx.op(v.name() + ": " + v.storeys() + " storeys, " + v.shape().name().toLowerCase() + " footprint " + v.w() + " cells");
                }
                // atrium edge: galleries open onto the void behind a colonnade
                for (int z = c - a - 2; z <= c + a + 2; z++)
                    for (int x = c - a - 2; x <= c + a + 2; x++) {
                        if (!core.covers(x, z)) continue;
                        boolean edge = g.get(x + 1, G + 1, z) == Cell.KEEP || g.get(x - 1, G + 1, z) == Cell.KEEP
                            || g.get(x, G + 1, z + 1) == Cell.KEEP || g.get(x, G + 1, z - 1) == Cell.KEEP;
                        if (!edge) continue;
                        for (int s = 0; s < core.storeys(); s++) {
                            int yb = G + 2 * s;
                            byte cur = g.get(x, yb, z);
                            if (cur == Cell.WALL) {
                                g.set(x, yb, z, Cell.COLUMN, compOf[0]);
                                g.set(x, yb + 1, z, Cell.COLUMN, compOf[0]);
                            } else if (cur == Cell.MARK_A) {
                                g.set(x, yb, z, s == 0 ? Cell.PATH : Cell.FLOOR, compOf[0]);
                                g.set(x, yb + 1, z, Cell.AIR, compOf[0]);
                            }
                        }
                    }
                ctx.op("open the atrium edge of every gallery into a colonnade");
                // tower roofs without gardens get stepped copper cupolas
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    if (!v.role().equals("tower") || v.terraced()) continue;
                    double r = v.w() / 2.0;
                    for (int k = 1; r - k * 0.9 > 0.4; k++) {
                        double rr = r - k * 0.9;
                        for (int z = v.z0(); z < v.z1(); z++)
                            for (int x = v.x0(); x < v.x1(); x++) {
                                double dx = x + 0.5 - v.cx(), dz = z + 0.5 - v.cz();
                                if (dx * dx + dz * dz <= rr * rr) g.set(x, v.top() + k, z, Cell.ROOF, compOf[i]);
                            }
                    }
                    g.set(v.cx() - 1, v.top() + 5, v.cz() - 1, Cell.LIGHT, compOf[i]);
                    ctx.op("stepped copper cupola on " + v.name());
                }
                // platforms: deck, pavilion, piers (grounded) or inverted rock (floating)
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    if (!v.role().equals("platform")) continue;
                    int y = v.y0();
                    for (int z = v.z0(); z < v.z1(); z++)
                        for (int x = v.x0(); x < v.x1(); x++) {
                            if (g.get(x, y, z) == Cell.EMPTY) g.set(x, y, z, Cell.TERRACE, compOf[i]);
                        }
                    // pavilion: four columns and a canopy
                    int px0 = v.cx() - 2, pz0 = v.cz() - 2;
                    // columns stand on the deck, the canopy rests on the columns
                    for (int[] q : new int[][]{{0, 0}, {3, 0}, {0, 3}, {3, 3}})
                        for (int yy = y + 1; yy < y + 3; yy++) g.set(px0 + q[0], yy, pz0 + q[1], Cell.COLUMN, compOf[i]);
                    for (int z = pz0; z < pz0 + 4; z++) for (int x = px0; x < px0 + 4; x++) g.set(x, y + 3, z, Cell.CANOPY, compOf[i]);
                    g.set(v.cx(), y + 1, v.cz(), Cell.LIGHT, compOf[i]);
                    if (v.floating()) {
                        Rng r = ctx.rng.fork("island" + i);
                        for (int k = 1; k <= 7; k++) {
                            double rr = 4.6 - k * 0.62 + (r.nextDouble() - 0.5) * 0.6;
                            for (int z = v.z0() - 1; z <= v.z1(); z++)
                                for (int x = v.x0() - 1; x <= v.x1(); x++) {
                                    double dx = x + 0.5 - v.cx(), dz = z + 0.5 - v.cz();
                                    if (dx * dx + dz * dz <= rr * rr) g.set(x, y - k, z, Cell.TERRAIN, compOf[i]);
                                }
                        }
                        ctx.op(v.name() + " floats on an inverted rock (fantasy mode)");
                    } else {
                        for (int[] q : new int[][]{{v.x0(), v.z0()}, {v.x1() - 1, v.z0()}, {v.x0(), v.z1() - 1}, {v.x1() - 1, v.z1() - 1},
                            {v.cx(), v.z0()}, {v.cx(), v.z1() - 1}, {v.x0(), v.cz()}, {v.x1() - 1, v.cz()}}) {
                            for (int yy = y - 1; yy >= 0; yy--) {
                                byte b = g.get(q[0], yy, q[1]);
                                if (b == Cell.TERRAIN) break;
                                g.set(q[0], yy, q[1], Cell.SUPPORT, compOf[i]);
                            }
                        }
                        ctx.op(v.name() + " carried on eight piers down to the rock");
                    }
                }
            });

        ctx.stage("circulation", "Rooms and circulation", "constructive",
            "Insert stair cores, cut doors between rooms and onto terraces, lay sky bridges and outdoor stairs; record the room graph.", () -> {
                pave(ctx, G, 0);
                // stair cores: four in the atrium tower, one per tower
                int rr = a + 2;
                int[][] corePos = {{c + rr, c - 1}, {c - rr - 2, c - 1}, {c - 1, c + rr}, {c - 1, c - rr - 2}};
                for (int[] q : corePos) ctx.kit.core(q[0], q[1], 2, G, core.top() + 1, compOf[0]);
                ctx.op("four stair cores in the atrium tower, ground to roof");
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    if (!v.role().equals("tower")) continue;
                    ctx.kit.core(v.cx() - 1, v.cz() - 1, 2, G, v.top() + 1, compOf[i]);
                    // ground door facing the library
                    int dx = c - v.cx(), dz = c - v.cz();
                    int d = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? 0 : 1) : (dz > 0 ? 2 : 3);
                    doorAlong(ctx, v.cx(), G, v.cz(), d, 8, compOf[i]);
                }
                // doors between rings (and core) on every shared storey, at the four cardinal axes
                List<Volume> chain = new ArrayList<>();
                chain.add(core);
                for (Volume v : m.volumes()) if (v.role().equals("ring")) chain.add(v);
                for (int k = 1; k < chain.size(); k++) {
                    Volume in = chain.get(k - 1), out = chain.get(k);
                    for (int d = 0; d < 4; d++) {
                        for (int s = 0; s < out.storeys(); s++) {
                            int y = G + 2 * s;
                            int sx0 = c + AX[d] * (in.w() / 2 - 2) - (d == 1 ? 1 : 0), sz0 = c + AZ[d] * (in.w() / 2 - 2) - (d == 3 ? 1 : 0);
                            int ox = d >= 2 ? (d == 2 ? 0 : 0) : 0;
                            doorAlong(ctx, sx0 + ox, y, sz0, d, 8, 0);
                        }
                        // from the inner volume onto the outer ring's roof terrace
                        int yt = out.top();
                        if (yt < in.top()) {
                            int sx0 = c + AX[d] * (in.w() / 2 - 2) - (d == 1 ? 1 : 0), sz0 = c + AZ[d] * (in.w() / 2 - 2) - (d == 3 ? 1 : 0);
                            doorAlong(ctx, sx0, yt, sz0, d, 6, 0);
                        }
                    }
                }
                ctx.op("doors on every storey between neighbouring rings and onto each terrace");
                // outermost ring opens onto the plaza; main entrance on the south axis
                Volume outer = chain.get(chain.size() - 1);
                for (int d = 0; d < 4; d++) {
                    int sx0 = c + AX[d] * (outer.w() / 2 - 2) - (d == 1 ? 1 : 0), sz0 = c + AZ[d] * (outer.w() / 2 - 2) - (d == 3 ? 1 : 0);
                    doorAlong(ctx, sx0, G, sz0, d, 6, 0);
                }
                ctx.entrance(c, G, c + outer.w() / 2 + 1);
                ctx.op("main entrance on the south axis of the plaza");
                // outdoor stairs between terraces, rotating around the rings
                for (int k = chain.size() - 1; k >= 1; k--) {
                    Volume ring = chain.get(k);
                    int baseY = k == chain.size() - 1 ? G : chain.get(k + 1).top();
                    int rise = ring.top() - baseY;
                    if (rise <= 0) continue;
                    for (int j = 0; j < 2; j++) {
                        int d = (k + 2 * j) % 4;
                        terraceStair(ctx, c, ring.w() / 2, d, baseY, rise, compOf[0]);
                    }
                }
                ctx.op("outdoor stairs climb from the plaza from terrace to terrace");
                // sky bridges
                int bridges = 0;
                for (Link l : m.links()) {
                    if (!l.enabled()) continue;
                    Volume va = m.volumes().get(l.a()), vb = m.volumes().get(l.b());
                    double ang = Math.atan2(vb.cz() - va.cz(), vb.cx() - va.cx());
                    double ra = edgeRadius(va), rb = edgeRadius(vb);
                    double ax = va.cx() + Math.cos(ang) * (ra - 0.6), az = va.cz() + Math.sin(ang) * (ra - 0.6);
                    double bx = vb.cx() - Math.cos(ang) * (rb - 0.6), bz = vb.cz() - Math.sin(ang) * (rb - 0.6);
                    int bc = ctx.comps.add(l.kind().substring(0, 1).toUpperCase() + l.kind().substring(1) + " from " + va.name() + " to " + vb.name()
                        + " (level " + l.level() + ")", "bridge", Material.TIMBER, true, va.floating() || vb.floating());
                    List<int[]> deck = new ArrayList<>();
                    ctx.kit.bridge(ax - 0.5, az - 0.5, bx - 0.5, bz - 0.5, l.level(), 2, bc, deck);
                    bridges++;
                    if (!fantasy || !(va.floating() || vb.floating())) {
                        // piers every few cells where something solid lies below
                        for (int q = 3; q < deck.size(); q += 7) {
                            int[] cell = deck.get(q);
                            int yy = cell[1] - 1;
                            while (yy > 0 && g.get(cell[0], yy, cell[2]) == Cell.EMPTY) yy--;
                            byte below = g.get(cell[0], yy, cell[2]);
                            if (below == Cell.TERRACE || below == Cell.PATH || below == Cell.TERRAIN || below == Cell.ROOF) {
                                int from = below == Cell.TERRACE || below == Cell.PATH ? yy : yy + 1; // piers pass through paving to the structure below
                                for (int y2 = from; y2 < cell[1]; y2++) g.set(cell[0], y2, cell[2], Cell.SUPPORT, bc);
                            }
                        }
                    }
                }
                ctx.op(bridges + " bridges laid between towers at storey levels");
                // grounded platforms: stair up to the plaza
                for (int i = 0; i < m.volumes().size(); i++) {
                    Volume v = m.volumes().get(i);
                    if (!v.role().equals("platform") || v.floating()) continue;
                    platformStair(ctx, v, c, compOf[i]);
                }
            });

        ctx.stage("growth", "Rule-driven growth", "rules",
            "Rewrite rules decide window bays, open the atrium galleries and grow gardens across the terraces.",
            () -> ctx.runProgram("growth", defaultProgram("growth", p)));

        ctx.stage("validation", "Constraint checks", "validation",
            "Traverse the circulation graph from the entrance and test voids, headroom, occupancy and support.",
            () -> standardValidation(ctx, !fantasy, 3, 9, 30));

        ctx.stage("detailing", "Architectural detailing", "rules",
            "Bookshelves, trees, shrubs and lanterns are placed by local rules.",
            () -> ctx.runProgram("detailing", defaultProgram("detailing", p)));

        ctx.stage("refine", "Composition refinement", "rules",
            "Merge some window bays into ribbons and keep doorways clear, then re-check circulation.", () -> {
                ctx.runProgram("refine", defaultProgram("refine", p));
                var conn = studio.forma.engine.arch.Validator.connectivity(g, ctx.comps, ctx.entranceIndices());
                ctx.op(String.format("re-check after detailing: %d of %d walkable cells reachable", conn.reachable(), conn.standable()));
                if (!conn.unreachableRequired().isEmpty())
                    ctx.report.add("connectivity-final", "Circulation after detailing", "hard",
                        studio.forma.engine.arch.ConstraintReport.Status.FAIL, "unreachable: " + String.join(", ", conn.unreachableRequired()), 0);
            });
    }

    static double edgeRadius(Volume v) {
        if (v.shape() == Shape.BOX) return Math.min(v.w(), v.d()) / 2.0;
        return v.w() / 2.0;
    }

    /** A stair hugging the outer facade of a ring at cardinal direction d, from baseY up to the ring roof. */
    private void terraceStair(GenContext ctx, int c, int ro, int d, int baseY, int rise, int comp) {
        Grid g = ctx.grid;
        // normal coordinate of the first cell outside the ring on side d; tangent runs perpendicular
        int sgn = (d == 0 || d == 2) ? -1 : 1;     // stair rises toward -tangent on +x/+z sides, +tangent otherwise
        int nCoord = (d == 0 || d == 2) ? c + ro : c - ro - 1;
        int landingT = sgn < 0 ? c + 2 : c - 3;   // land just beside the door axis, keeping doorways free
        int start = landingT - sgn * rise;
        int dir; // stair direction code
        boolean alongZ = d == 0 || d == 1;
        if (alongZ) dir = sgn > 0 ? 2 : 3; else dir = sgn > 0 ? 0 : 1;
        int landing = landingT;
        int backT = start - sgn;
        java.util.function.BiFunction<Integer, Integer, int[]> xz = (nc, tc) -> alongZ ? new int[]{nc, tc} : new int[]{tc, nc};
        int[] back = xz.apply(nCoord, backT);
        if (!Cell.isStandable(g.get(back[0], baseY, back[1]))) return;
        for (int i = 0; i < rise; i++) {
            int[] q = xz.apply(nCoord, start + sgn * i);
            byte cur = g.get(q[0], baseY + i, q[1]);
            if (!(cur == Cell.EMPTY || cur == Cell.TERRACE || cur == Cell.PATH || cur == Cell.GRASS)) return;
        }
        int[] land = xz.apply(nCoord, landing);
        int inward = (d == 0 || d == 2) ? -1 : 1;
        int[] roofCell = xz.apply(nCoord + inward, landing);
        if (!Cell.isStandable(g.get(roofCell[0], baseY + rise, roofCell[1]))) return;
        int[] s0 = xz.apply(nCoord, start);
        ctx.kit.stairRun(s0[0], baseY, s0[1], dir, rise, comp, true, baseY);
        g.set(land[0], baseY + rise, land[1], Cell.TERRACE, comp);
        for (int y = baseY + rise - 1; y >= baseY; y--) {
            byte b = g.get(land[0], y, land[1]);
            if (b == Cell.EMPTY || b == Cell.TERRACE || b == Cell.PATH || b == Cell.GRASS) g.set(land[0], y, land[1], Cell.WALL, comp);
        }
    }

    /** Connects a grounded cloud platform to the plaza: a deck extension plus a straight stair, trying both axes toward the library. */
    private void platformStair(GenContext ctx, Volume v, int c, int comp) {
        Grid g = ctx.grid;
        int dx = c - v.cx(), dz = c - v.cz();
        int primary = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? 0 : 1) : (dz > 0 ? 2 : 3);
        int secondary = Math.abs(dx) >= Math.abs(dz) ? (dz > 0 ? 2 : 3) : (dx > 0 ? 0 : 1);
        int rise = G - v.y0();
        for (int d : new int[]{primary, secondary}) {
            for (int lane = 0; lane < 3; lane++) {
                // lanes: centre line, then one cell to either side of it
                int off = lane == 0 ? 0 : (lane == 1 ? 1 : -1);
                int sx = v.cx() + (d >= 2 ? off : 0), sz = v.cz() + (d < 2 ? off : 0);
                while (inBox(v, sx + AX[d], sz + AZ[d])) { sx += AX[d]; sz += AZ[d]; }
                for (int ext = 0; ext < 14; ext++) {
                    int bx = sx + AX[d] * (ext + 1), bz = sz + AZ[d] * (ext + 1);
                    int lx = bx + AX[d] * rise, lz = bz + AZ[d] * rise;
                    byte top = g.get(lx, G, lz);
                    boolean landing = top == Cell.PATH || (top == Cell.EMPTY && g.get(lx, G - 1, lz) == Cell.TERRAIN);
                    if (!landing) continue;
                    boolean clear = true;
                    for (int k = 0; k < ext && clear; k++) {
                        byte cell = g.get(sx + AX[d] * (k + 1), v.y0(), sz + AZ[d] * (k + 1));
                        clear = cell == Cell.EMPTY || cell == Cell.TERRAIN || cell == Cell.TERRACE;
                    }
                    for (int i = 0; i < rise && clear; i++) {
                        byte cell = g.get(bx + AX[d] * i, v.y0() + i, bz + AZ[d] * i);
                        clear = cell == Cell.EMPTY || cell == Cell.TERRAIN || cell == Cell.PATH;
                    }
                    if (!clear) continue;
                    for (int k = 0; k < ext; k++) g.set(sx + AX[d] * (k + 1), v.y0(), sz + AZ[d] * (k + 1), Cell.TERRACE, comp);
                    for (int i = 0; i < rise; i++) {
                        int cx = bx + AX[d] * i, cz = bz + AZ[d] * i;
                        for (int y = v.y0() + i; y <= G + 1; y++) if (g.get(cx, y, cz) == Cell.TERRAIN) g.set(cx, y, cz, Cell.EMPTY, comp);
                    }
                    ctx.kit.stairRun(bx, v.y0(), bz, d, rise, comp, true, v.y0()); // a solid stringer under the flight
                    if (g.get(lx, G, lz) == Cell.EMPTY) g.set(lx, G, lz, Cell.PATH, comp);
                    ctx.op(v.name() + " reached by a stair from the plaza");
                    return;
                }
            }
        }
        ctx.op(v.name() + ": no stair position found");
    }

    private static boolean inBox(Volume v, int x, int z) {
        return x >= v.x0() && x < v.x1() && z >= v.z0() && z < v.z1();
    }

    // ---- refinement profile -----------------------------------------------------------------

    @Override
    public RefineProfile refineProfile(Params p, Massing probe) {
        RefineProfile rp = new RefineProfile();
        int c = probe.sx() / 2;
        int rout = 0;
        for (int i = 0; i < probe.volumes().size(); i++) {
            Volume v = probe.volumes().get(i);
            switch (v.role()) {
                case "tower" -> { rp.movable.add(i); rp.resizable.add(i); rp.terraceToggle.add(i); }
                case "core" -> { rp.resizable.add(i); rp.courtyardAdjust.add(i); }
                case "ring" -> rout = Math.max(rout, v.w() / 2);
                case "platform" -> rp.movable.add(i);
                default -> {}
            }
        }
        for (int i = 0; i < probe.links().size(); i++) rp.linkToggle.add(i);
        rp.minStoreys = 3;
        rp.maxStoreys = 22;
        rp.minCourtyard = 2;
        rp.maxCourtyard = 6;
        final int routF = rout;
        rp.objective("circulation", "Connected circulation", "Every tower should be reached by at least one sky bridge; long spans cost a little.",
            m -> Objectives.linkage(m, Objectives.role("tower"), 0.02));
        rp.objective("daylight", "Daylight access", "Towers should not crowd each other, and the atrium should stay wide for its height.",
            m -> Objectives.crowding(m, v -> v.role().equals("tower"), 7) + Objectives.courtLight(m, 0, 0.32));
        rp.objective("proportion", "Balanced proportions", "Towers about 2.6 times as tall as they are wide.",
            m -> Objectives.slenderness(m, Objectives.role("tower"), 2.6));
        rp.objective("void", "Preserved central void", "The atrium keeps a generous radius.",
            m -> Math.max(0, 4 - m.volumes().get(0).courtyard()) * 0.4);
        rp.objective("silhouette", "Strong silhouette", "One dominant atrium tower; towers near 72% of its height, falling off outward.",
            m -> Objectives.peakSilhouette(m, 0, "tower", 0.72) + Objectives.balance(m, v -> v.role().equals("tower")) * 0.5);
        rp.objective("variety", "Controlled variety", "Tower heights vary a little (about 12%), not chaotically.",
            m -> Objectives.variety(m, Objectives.role("tower"), 0.12));
        rp.objective("greenery", "Greenery", "About half of the towers carry roof gardens.",
            m -> Objectives.greenery(m, Objectives.role("tower"), 0.5));
        rp.objective("density", "Density", "Built footprint near 30% of the site.",
            m -> Objectives.density(m, v -> !v.role().equals("platform"), m.sx() * m.sz(), 0.30));
        rp.hard("site bounds", m -> Objectives.withinGrid(m, 3));
        rp.hard("towers clear of the terraces", m -> Objectives.outsideRadius(m, Objectives.role("tower"), c, c, routF + 3));
        rp.hard("towers apart", m -> Objectives.separated(m, Objectives.role("tower"), Objectives.role("tower"), 3));
        rp.hard("platforms apart", m -> Objectives.separated(m, Objectives.role("platform"), v -> !v.role().equals("core") && !v.role().equals("ring"), 2));
        rp.hard("platforms clear of the terraces", m -> Objectives.outsideRadius(m, Objectives.role("platform"), c, c, routF + 1));
        if (!p.b("fantasy")) rp.hard("platforms near the pinnacle", m -> {
            for (Volume v : m.volumes())
                if (v.role().equals("platform") && Math.hypot(v.cx() - c, v.cz() - c) - Objectives.radius(v) > routF + 9)
                    return v.name() + " drifts too far from the pinnacle to be reached";
            return null;
        });
        rp.hard("bridges", m -> Objectives.linksWithin(m, MAX_BRIDGE));
        rp.hard("tower below atrium tower", m -> {
            Volume core = m.volumes().get(0);
            for (Volume v : m.volumes()) if (v.role().equals("tower") && v.storeys() >= core.storeys()) return v.name() + " would outgrow the atrium tower";
            for (Volume v : m.volumes()) if (v.role().equals("ring") && v.storeys() >= core.storeys()) return "a ring would outgrow the atrium tower";
            return null;
        });
        return rp;
    }
}
