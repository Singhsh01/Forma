package studio.forma.engine.rules;

import studio.forma.engine.core.Cancellation;
import studio.forma.engine.core.Grid;
import studio.forma.engine.core.History;
import studio.forma.engine.core.Rng;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Execution state for one rule program run: the grid, the seeded random stream, the rule log,
 * cancellation, a step budget, and a journal of changed cells (so cached matchers can refresh
 * only around cells that changed since they last ran).
 */
public final class RunContext implements AutoCloseable {
    public final Grid grid;
    public final Rng rng;
    public final RuleLog log;
    public final Cancellation cancel;
    public final History history;
    public final int maxSteps;
    int steps;
    boolean hitStepLimit;
    private IntConsumer onStep;

    // change journal
    int[] journal = new int[4096];
    int journalLen;
    private final Grid.ChangeSink previousSink;

    // scratch used by all-nodes for conflict detection
    final int[] touchStamp;
    int touchCounter = 1;

    public RunContext(Grid grid, Rng rng, RuleLog log, Cancellation cancel, History history, int maxSteps) {
        this.grid = grid;
        this.rng = rng;
        this.log = log;
        this.cancel = cancel;
        this.history = history;
        this.maxSteps = maxSteps;
        this.touchStamp = new int[grid.size()];
        this.previousSink = grid.sink();
        grid.setSink((i, s) -> {
            if (journalLen == journal.length) journal = Arrays.copyOf(journal, journalLen * 2);
            journal[journalLen++] = i;
            if (previousSink != null) previousSink.changed(i, s);
        });
    }

    public void onStep(IntConsumer listener) {
        this.onStep = listener;
    }

    public int steps() {
        return steps;
    }

    public boolean hitStepLimit() {
        return hitStepLimit;
    }

    /** Runs a node tree to completion (or until the step budget / cancellation stops it). */
    public int run(Node root) {
        root.reset();
        int start = steps;
        while (true) {
            if ((steps & 63) == 0) cancel.check();
            if (steps >= maxSteps) {
                hitStepLimit = true;
                break;
            }
            if (!root.step(this)) break;
            steps++;
            log.tick();
            if (onStep != null && (steps & 255) == 0) onStep.accept(steps);
        }
        return steps - start;
    }

    int nextTouchStamp() {
        touchCounter++;
        if (touchCounter == Integer.MAX_VALUE) {
            Arrays.fill(touchStamp, 0);
            touchCounter = 1;
        }
        return touchCounter;
    }

    @Override
    public void close() {
        grid.setSink(previousSink);
    }
}
