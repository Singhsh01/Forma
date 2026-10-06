package studio.forma.engine.rules;

import studio.forma.engine.core.Cell;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the characters of rule strings to cell-state sets (inputs) and single states (outputs).
 *
 * <p>{@code *} is reserved: as an input it matches any state, as an output it leaves the cell
 * unchanged. Union symbols (for example {@code .} = empty or interior air) may be added with
 * {@link #union}; unions are only valid in inputs.
 */
public final class Legend {
    public static final char WILDCARD = '*';
    private final Map<Character, Integer> masks = new LinkedHashMap<>();
    private final Map<Character, Integer> singles = new LinkedHashMap<>();

    public static Legend standard() {
        Legend l = new Legend();
        Cell.defaultLegend().forEach((c, s) -> {
            l.masks.put(c, 1 << s);
            l.singles.put(c, s);
        });
        l.union('.', Cell.EMPTY, Cell.AIR);
        l.union('_', Cell.EMPTY, Cell.AIR, Cell.KEEP);
        l.union('#', Cell.WALL, Cell.WINDOW, Cell.ARCH, Cell.ROOF, Cell.TERRAIN, Cell.SUPPORT);
        l.union('^', Cell.FLOOR, Cell.TERRACE, Cell.GRASS, Cell.BRIDGE, Cell.PATH);
        l.union('i', Cell.AIR, Cell.FLOOR, Cell.SHELF);
        l.union('o', Cell.EMPTY, Cell.KEEP);
        return l;
    }

    public Legend union(char symbol, int... states) {
        if (symbol == WILDCARD) throw new RuleValidationException("'*' cannot be redefined");
        int m = 0;
        for (int s : states) {
            if (s < 0 || s >= Cell.COUNT) throw new RuleValidationException("unknown state " + s + " in union '" + symbol + "'");
            m |= 1 << s;
        }
        masks.put(symbol, m);
        singles.remove(symbol);
        return this;
    }

    public int inputMask(char c) {
        if (c == WILDCARD) return -1; // all bits
        Integer m = masks.get(c);
        if (m == null) throw new RuleValidationException("unknown input symbol '" + c + "'");
        return m;
    }

    /** @return the output state, or -1 for "leave unchanged" */
    public int output(char c) {
        if (c == WILDCARD) return -1;
        Integer s = singles.get(c);
        if (s == null) {
            if (masks.containsKey(c))
                throw new RuleValidationException("union symbol '" + c + "' cannot be used as an output");
            throw new RuleValidationException("unknown output symbol '" + c + "'");
        }
        return s;
    }

    public Map<Character, Integer> unions() {
        Map<Character, Integer> u = new LinkedHashMap<>();
        masks.forEach((c, m) -> {
            if (!singles.containsKey(c)) u.put(c, m);
        });
        return u;
    }
}
