package studio.forma.engine.presets;

/**
 * A generation parameter exposed to users. Every parameter listed by a preset changes the
 * geometry it produces; display-only controls live in the viewer, not here.
 */
public record ParamSpec(String key, String label, String help, String kind, double min, double max,
                        double step, double defaultValue, String group) {

    public static ParamSpec integer(String key, String label, String help, int min, int max, int def, String group) {
        return new ParamSpec(key, label, help, "int", min, max, 1, def, group);
    }

    public static ParamSpec real(String key, String label, String help, double min, double max, double step, double def, String group) {
        return new ParamSpec(key, label, help, "float", min, max, step, def, group);
    }

    public static ParamSpec toggle(String key, String label, String help, boolean def, String group) {
        return new ParamSpec(key, label, help, "bool", 0, 1, 1, def ? 1 : 0, group);
    }

    public double clamp(double v) {
        if (Double.isNaN(v)) return defaultValue;
        double c = Math.max(min, Math.min(max, v));
        if (kind.equals("int") || kind.equals("bool")) c = Math.round(c);
        return c;
    }
}
