package studio.forma.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything needed to reproduce a design: preset, seed, parameters, optional rule-program
 * overrides and the generator version. Same config + same version = identical output.
 */
public record GenerationConfig(String preset, long seed, Map<String, Double> params,
                               Map<String, String> ruleOverrides, String generatorVersion) {

    public GenerationConfig {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        ruleOverrides = ruleOverrides == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ruleOverrides));
        if (generatorVersion == null || generatorVersion.isBlank()) generatorVersion = Version.ENGINE;
        if (preset == null || preset.isBlank()) throw new IllegalArgumentException("preset is required");
    }

    public static GenerationConfig of(String preset, long seed) {
        return new GenerationConfig(preset, seed, Map.of(), Map.of(), Version.ENGINE);
    }

    public GenerationConfig withParam(String key, double value) {
        Map<String, Double> p = new LinkedHashMap<>(params);
        p.put(key, value);
        return new GenerationConfig(preset, seed, p, ruleOverrides, generatorVersion);
    }

    public GenerationConfig withSeed(long s) {
        return new GenerationConfig(preset, s, params, ruleOverrides, generatorVersion);
    }
}
