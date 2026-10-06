package studio.forma.engine.rules;

import java.util.List;

/** Runs children one after another; each child runs until it reports no further progress. */
public final class SequenceNode extends Node {
    private final List<Node> children;
    private int current;

    public SequenceNode(String name, List<Node> children) {
        super(name);
        if (children == null || children.isEmpty()) throw new RuleValidationException("sequence '" + name + "' is empty");
        this.children = List.copyOf(children);
    }

    @Override
    public String type() {
        return "sequence";
    }

    @Override
    public void reset() {
        current = 0;
        children.get(0).reset();
    }

    @Override
    public boolean step(RunContext ctx) {
        while (current < children.size()) {
            if (children.get(current).step(ctx)) return true;
            current++;
            if (current < children.size()) children.get(current).reset();
        }
        return false;
    }

    public List<Node> children() {
        return children;
    }
}
