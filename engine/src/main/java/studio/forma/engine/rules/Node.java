package studio.forma.engine.rules;

/**
 * A node of a rule program. {@link #step} performs one unit of work and reports whether the
 * grid changed; a node that returns {@code false} is finished until it is {@link #reset}.
 */
public abstract class Node {
    public final String name;

    protected Node(String name) {
        this.name = name;
    }

    public abstract boolean step(RunContext ctx);

    public void reset() {}

    public abstract String type();
}
