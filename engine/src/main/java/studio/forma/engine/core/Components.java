package studio.forma.engine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of named architectural components (a tower, a terrace ring, a bridge...).
 * Component 0 is reserved for "site" (terrain, unassigned cells).
 */
public final class Components {
    public enum Material {
        SANDSTONE, PLASTER, TERRACOTTA, COPPER, TIMBER, CONCRETE, BASALT, MARBLE, BRASS, ROCK
    }

    public record Component(int id, String name, String kind, Material material,
                            boolean required, boolean floating) {}

    private final List<Component> list = new ArrayList<>();
    private final java.util.Map<Integer, Material> roofs = new java.util.HashMap<>();

    /** Material used to draw ROOF cells of a component (default: copper patina). */
    public void setRoof(int id, Material m) {
        roofs.put(id, m);
    }

    public Material roof(int id) {
        return roofs.getOrDefault(id, Material.COPPER);
    }

    private final java.util.Set<Integer> litGlass = new java.util.HashSet<>();

    /** Glass of this component is drawn lit from inside (warm, emissive) instead of clear. */
    public void setGlass(int id, boolean lit) {
        if (lit) litGlass.add(id); else litGlass.remove(id);
    }

    public boolean litGlass(int id) {
        return litGlass.contains(id);
    }

    public Components() {
        list.add(new Component(0, "Site", "site", Material.ROCK, false, false));
    }

    public int add(String name, String kind, Material material, boolean required, boolean floating) {
        if (list.size() >= Short.MAX_VALUE) throw new IllegalStateException("too many components");
        int id = list.size();
        list.add(new Component(id, name, kind, material, required, floating));
        return id;
    }

    public int add(String name, String kind, Material material) {
        return add(name, kind, material, false, false);
    }

    public Component get(int id) {
        return id >= 0 && id < list.size() ? list.get(id) : list.get(0);
    }

    public int size() {
        return list.size();
    }

    public List<Component> all() {
        return Collections.unmodifiableList(list);
    }
}
