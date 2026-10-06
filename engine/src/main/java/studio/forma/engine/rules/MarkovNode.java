package studio.forma.engine.rules;

import java.util.List;

/**
 * {@code markov} node: on every step, the first child (in order) that can make progress is
 * stepped. Earlier children therefore take priority and are revisited after later ones change
 * the grid, exactly like an ordered Markov algorithm. Finishes when no child can progress.
 */
public final class MarkovNode extends Node {
    private final List<Node> children;

    public MarkovNode(String name, List<Node> children) {
        super(name);
        if (children == null || children.isEmpty()) throw new RuleValidationException("markov '" + name + "' is empty");
        this.children = List.copyOf(children);
    }

    @Override
    public String type() {
        return "markov";
    }

    @Override
    public void reset() {
        for (Node c : children) c.reset();
    }

    @Override
    public boolean step(RunContext ctx) {
        for (Node c : children) {
            if (c.step(ctx)) return true;
        }
        return false;
    }

    public List<Node> children() {
        return children;
    }
}
