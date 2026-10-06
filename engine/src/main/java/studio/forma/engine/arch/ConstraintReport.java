package studio.forma.engine.arch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Results of architectural checks. Each check is either a hard requirement or a heuristic;
 * heuristics (support, daylight) are conceptual design aids, not structural or code analysis.
 */
public final class ConstraintReport {
    public enum Status { PASS, WARN, FAIL }

    public record Check(String id, String label, String kind, Status status, String detail, double value) {}

    private final List<Check> checks = new ArrayList<>();

    public void add(String id, String label, String kind, Status status, String detail, double value) {
        checks.add(new Check(id, label, kind, status, detail, value));
    }

    public List<Check> checks() {
        return Collections.unmodifiableList(checks);
    }

    public boolean allHardPass() {
        for (Check c : checks) if (c.kind().equals("hard") && c.status() == Status.FAIL) return false;
        return true;
    }

    public Check get(String id) {
        for (Check c : checks) if (c.id().equals(id)) return c;
        return null;
    }
}
