package studio.forma.engine.presets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All available architectural worlds, in display order. */
public final class PresetRegistry {
    private PresetRegistry() {}

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        register(new LibraryPreset());
        register(new CliffsidePreset());
        register(new GardensPreset());
        register(new CathedralPreset());
        register(new CanalPreset());
        register(new OrganicPreset());
        register(new EscherPreset());
    }

    static void register(Preset p) {
        PRESETS.put(p.id(), p);
    }

    public static Preset get(String id) {
        Preset p = PRESETS.get(id);
        if (p == null) throw new IllegalArgumentException("unknown preset '" + id + "' (available: " + String.join(", ", PRESETS.keySet()) + ")");
        return p;
    }

    public static List<Preset> all() {
        return Collections.unmodifiableList(new ArrayList<>(PRESETS.values()));
    }
}
