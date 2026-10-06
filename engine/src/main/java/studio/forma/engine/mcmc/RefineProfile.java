package studio.forma.engine.mcmc;

import studio.forma.engine.arch.Massing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * What a preset lets MCMC change and how it scores massing states.
 *
 * <p>Objectives are explicit design preferences (each a non-negative penalty, 0 = ideal), not
 * objective measures of beauty. Hard constraints define the valid state space.
 */
public final class RefineProfile {
    public record Objective(String key, String label, String description, ToDoubleFunction<Massing> penalty) {}

    public record Hard(String label, java.util.function.Function<Massing, String> check) {}

    /** Which volumes (by index in the initial massing) each move family may touch. */
    public final List<Integer> movable = new ArrayList<>();
    public final List<Integer> resizable = new ArrayList<>();
    public final List<Integer> terraceToggle = new ArrayList<>();
    public final List<Integer> courtyardAdjust = new ArrayList<>();
    public final List<Integer> linkToggle = new ArrayList<>();
    public final List<Objective> objectives = new ArrayList<>();
    public final List<Hard> hard = new ArrayList<>();
    public int minStoreys = 1, maxStoreys = 30, minCourtyard = 0, maxCourtyard = 12;

    public RefineProfile objective(String key, String label, String description, ToDoubleFunction<Massing> f) {
        objectives.add(new Objective(key, label, description, f));
        return this;
    }

    public RefineProfile hard(String label, java.util.function.Function<Massing, String> check) {
        hard.add(new Hard(label, check));
        return this;
    }
}
