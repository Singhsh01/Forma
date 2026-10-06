package studio.forma.engine.presets;

import studio.forma.engine.GenContext;
import studio.forma.engine.arch.Massing;
import studio.forma.engine.core.Rng;
import studio.forma.engine.mcmc.RefineProfile;

import java.util.List;

/**
 * An architectural world. A preset composes a {@link Massing} from parameters and a seed, then
 * realises it into a voxel design through named stages (site, masses, circulation, rule growth,
 * validation, detailing, refinement). Realisation depends only on the massing, the parameters,
 * the seed and rule overrides, so MCMC can refine the massing and re-realise it.
 */
public interface Preset {
    String id();

    String title();

    String summary();

    /** Honest notes shown with the preset (e.g. what is a heuristic, what is an illusion). */
    List<String> notes();

    List<ParamSpec> params();

    /** Ids of the rule programs this preset runs (their source is reported per run and can be overridden). */
    List<String> programIds();

    /** The default source of a rule program for the given parameters. */
    String defaultProgram(String id, Params p);

    int[] gridSize(Params p);

    Massing compose(Params p, Rng rng);

    void realize(Massing m, GenContext ctx);

    /**
     * What MCMC may change and how it scores states, built for the massing being refined: move
     * families refer to volumes and links of {@code m} by index.
     */
    RefineProfile refineProfile(Params p, Massing m);

    /** Suggested camera framing in grid units: target (x,y,z), distance, azimuth deg, elevation deg. */
    double[] camera(Params p);

    /** Presentation hints for the viewer: sky palette, clouds, preferred projection. Purely visual. */
    default Atmosphere atmosphere(Params p) {
        return new Atmosphere("dusk", false, "perspective", 0);
    }

    record Atmosphere(String sky, boolean clouds, String projection, int cloudLevel) {}
}
