package studio.forma.engine.presets;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolved parameter values: user input clamped to each spec, defaults filled in. */
public final class Params {
    private final Map<String, Double> values;

    private Params(Map<String, Double> values) {
        this.values = values;
    }

    public static Params resolve(List<ParamSpec> specs, Map<String, Double> input) {
        Map<String, Double> v = new LinkedHashMap<>();
        for (ParamSpec s : specs) {
            Double in = input == null ? null : input.get(s.key());
            v.put(s.key(), in == null ? s.defaultValue() : s.clamp(in));
        }
        return new Params(v);
    }

    public double get(String key) {
        Double d = values.get(key);
        if (d == null) throw new IllegalArgumentException("unknown parameter '" + key + "'");
        return d;
    }

    public int i(String key) {
        return (int) Math.round(get(key));
    }

    public boolean b(String key) {
        return get(key) >= 0.5;
    }

    public Map<String, Double> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
