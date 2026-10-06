package studio.forma.engine.mcmc;

import studio.forma.engine.arch.Massing;
import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Rng;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCMC over {@link Massing} states.
 *
 * <p><b>Proposal kernel.</b> A move family is chosen with fixed probabilities, then one element
 * uniformly from that family's fixed candidate list (built from the initial massing and never
 * changed during the run), then a direction uniformly:
 * <ul>
 *   <li>shift a volume one cell in +x, -x, +z or -z</li>
 *   <li>add or remove one storey</li>
 *   <li>toggle a roof terrace</li>
 *   <li>grow or shrink a courtyard / atrium by one cell</li>
 *   <li>enable or disable a candidate bridge</li>
 * </ul>
 * Every move's reverse is a move of the same family on the same element in the opposite
 * direction (a toggle is its own reverse), chosen with the same probability, so the kernel is
 * symmetric: q(x'|x) = q(x|x'). The Metropolis-Hastings ratio then reduces to
 * exp(-(E' - E) / T). Out-of-range results are rejected by hard constraints.
 */
public final class MassingRefiner {
    private final RefineProfile profile;
    private final Map<String, Double> weights;

    public MassingRefiner(RefineProfile profile, Map<String, Double> weights) {
        this.profile = profile;
        this.weights = weights == null ? Map.of() : weights;
    }

    public MetropolisSampler.Energy<Massing> energy() {
        return m -> {
            Map<String, Double> comps = new LinkedHashMap<>();
            double total = 0;
            for (RefineProfile.Objective o : profile.objectives) {
                double w = weights.getOrDefault(o.key(), 1.0);
                double v = w * o.penalty().applyAsDouble(m);
                comps.put(o.key(), v);
                total += v;
            }
            return new MetropolisSampler.Breakdown(total, comps);
        };
    }

    public List<MetropolisSampler.HardConstraint<Massing>> constraints() {
        List<MetropolisSampler.HardConstraint<Massing>> out = new ArrayList<>();
        for (RefineProfile.Hard h : profile.hard) out.add(m -> h.check().apply(m));
        out.add(m -> {
            for (int i : profile.resizable) {
                int s = m.volumes().get(i).storeys();
                if (s < profile.minStoreys || s > profile.maxStoreys) return "storeys out of range";
            }
            for (int i : profile.courtyardAdjust) {
                int c = m.volumes().get(i).courtyard();
                if (c < profile.minCourtyard || c > profile.maxCourtyard) return "courtyard out of range";
            }
            return null;
        });
        return out;
    }

    public MetropolisSampler.Kernel<Massing> kernel() {
        // fixed family weights; families with no candidates get weight 0 for the whole run
        double[] fw = {
            profile.movable.isEmpty() ? 0 : 0.36,
            profile.resizable.isEmpty() ? 0 : 0.24,
            profile.terraceToggle.isEmpty() ? 0 : 0.12,
            profile.courtyardAdjust.isEmpty() ? 0 : 0.08,
            profile.linkToggle.isEmpty() ? 0 : 0.20
        };
        double sum = 0;
        for (double v : fw) sum += v;
        final double total = sum;
        return (m, rng) -> {
            if (total == 0) return null;
            double u = rng.nextDouble() * total;
            int fam = 0;
            while (fam < fw.length - 1 && u >= fw[fam]) {
                u -= fw[fam];
                fam++;
            }
            switch (fam) {
                case 0 -> {
                    int i = profile.movable.get(rng.nextInt(profile.movable.size()));
                    int d = rng.nextInt(4);
                    Massing.Volume v = m.volumes().get(i);
                    int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
                    String dir = new String[]{"east", "west", "south", "north"}[d];
                    return MetropolisSampler.Proposal.symmetric(m.withVolume(i, v.withCenter(v.cx() + dx, v.cz() + dz)),
                        "shift " + v.name() + " " + dir);
                }
                case 1 -> {
                    int i = profile.resizable.get(rng.nextInt(profile.resizable.size()));
                    int d = rng.nextInt(2) == 0 ? 1 : -1;
                    Massing.Volume v = m.volumes().get(i);
                    return MetropolisSampler.Proposal.symmetric(m.withVolume(i, v.withStoreys(v.storeys() + d)),
                        (d > 0 ? "raise " : "lower ") + v.name() + " to " + (v.storeys() + d) + " storeys");
                }
                case 2 -> {
                    int i = profile.terraceToggle.get(rng.nextInt(profile.terraceToggle.size()));
                    Massing.Volume v = m.volumes().get(i);
                    return MetropolisSampler.Proposal.symmetric(m.withVolume(i, v.withTerraced(!v.terraced())),
                        (v.terraced() ? "remove roof garden from " : "add roof garden to ") + v.name());
                }
                case 3 -> {
                    int i = profile.courtyardAdjust.get(rng.nextInt(profile.courtyardAdjust.size()));
                    int d = rng.nextInt(2) == 0 ? 1 : -1;
                    Massing.Volume v = m.volumes().get(i);
                    return MetropolisSampler.Proposal.symmetric(m.withVolume(i, v.withCourtyard(v.courtyard() + d)),
                        (d > 0 ? "widen " : "narrow ") + "the open court of " + v.name());
                }
                default -> {
                    int li = profile.linkToggle.get(rng.nextInt(profile.linkToggle.size()));
                    Massing.Link l = m.links().get(li);
                    String where = l.a() >= 0 && l.b() >= 0 && l.a() < m.volumes().size() && l.b() < m.volumes().size()
                        ? " between " + m.volumes().get(l.a()).name() + " and " + m.volumes().get(l.b()).name() : "";
                    return MetropolisSampler.Proposal.symmetric(m.withLink(li, l.withEnabled(!l.enabled())),
                        (l.enabled() ? "remove the " : "add a ") + l.kind() + where + " at level " + l.level());
                }
            }
        };
    }

    public MetropolisSampler.Result<Massing> run(Massing initial, int iterations, MetropolisSampler.Schedule schedule,
                                                 Rng rng, Cancellation cancel,
                                                 MetropolisSampler.Listener<Massing> listener) {
        MetropolisSampler<Massing> s = new MetropolisSampler<>(kernel(), energy(), constraints());
        return s.run(initial, iterations, schedule, rng, cancel, listener);
    }
}
