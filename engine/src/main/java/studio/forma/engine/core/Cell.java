package studio.forma.engine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit cell states used by every FORMA grid.
 *
 * <p>A cell is one grid unit (nominally 1.5 m wide; two cells make one storey of ~3 m).
 * The state describes what occupies the cell semantically. How a state is drawn
 * (a full block, a thin slab at the bottom of the cell, a pane, an instanced tree)
 * is decided later by {@code geometry.SceneBuilder}; generation and rendering are separate.
 */
public final class Cell {
    private Cell() {}

    public static final byte EMPTY = 0;      // exterior air
    public static final byte AIR = 1;        // interior air (inside a building envelope)
    public static final byte WALL = 2;       // masonry / structural mass
    public static final byte FLOOR = 3;      // walkable interior surface (slab at cell bottom)
    public static final byte WINDOW = 4;     // glazed opening in a wall plane, lit from inside
    public static final byte COLUMN = 5;     // slender vertical support
    public static final byte STAIR_XP = 6;   // stair rising one cell toward +x
    public static final byte STAIR_XN = 7;   // stair rising toward -x
    public static final byte STAIR_ZP = 8;   // stair rising toward +z
    public static final byte STAIR_ZN = 9;   // stair rising toward -z
    public static final byte BRIDGE = 10;    // walkable deck spanning between masses
    public static final byte TERRACE = 11;   // walkable outdoor slab (roof terrace, platform)
    public static final byte GRASS = 12;     // planted soil slab
    public static final byte VEG = 13;       // vegetation volume (shrub / tree crown)
    public static final byte WATER = 14;     // water body or channel
    public static final byte TERRAIN = 15;   // rock / earth
    public static final byte ROOF = 16;      // roof cap / parapet mass
    public static final byte GLASS = 17;     // glazed roof or curtain wall
    public static final byte LIGHT = 18;     // lantern / light fixture
    public static final byte ARCH = 19;      // arched opening module in a wall plane
    public static final byte KEEP = 20;      // reserved open space (atrium, courtyard) - must stay void
    public static final byte TRUNK = 21;     // tree trunk or branching column
    public static final byte CORE = 22;      // vertical circulation core (stair tower), walkable vertically
    public static final byte SUPPORT = 23;   // pier / strut carrying a platform
    public static final byte SHELF = 24;     // bookshelf / furniture against an interior wall (walkable floor)
    public static final byte DOOR = 25;      // entrance / doorway (walkable)
    public static final byte MARK_A = 26;    // temporary markers used between rule stages
    public static final byte MARK_B = 27;
    public static final byte MARK_C = 28;
    public static final byte MARK_D = 29;
    public static final byte CANOPY = 30;    // light roof / pergola top (not walkable)
    public static final byte PATH = 31;      // outdoor ground-level paving (walkable)

    public static final int COUNT = 32;

    private static final String[] NAMES = {
        "empty", "air", "wall", "floor", "window", "column", "stair+x", "stair-x", "stair+z", "stair-z",
        "bridge", "terrace", "grass", "vegetation", "water", "terrain", "roof", "glass", "light", "arch",
        "keep-open", "trunk", "core", "support", "shelf", "door", "mark-a", "mark-b", "mark-c", "mark-d",
        "canopy", "path"
    };

    /** Default single-character legend, used in rule strings and documentation. */
    public static final String DEFAULT_CHARS = "EAWFNCxXzZBTGVwRrglaKtOPSD1234yp";

    public static String name(int state) {
        return state >= 0 && state < COUNT ? NAMES[state] : "unknown(" + state + ")";
    }

    public static char symbol(int state) {
        return DEFAULT_CHARS.charAt(state);
    }

    public static Map<Character, Integer> defaultLegend() {
        Map<Character, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < COUNT; i++) m.put(DEFAULT_CHARS.charAt(i), i);
        return m;
    }

    public static boolean isStair(int s) {
        return s >= STAIR_XP && s <= STAIR_ZN;
    }

    /** Cells a person can stand in (the walking surface is at the bottom of the cell). */
    public static boolean isStandable(int s) {
        return s == FLOOR || s == BRIDGE || s == TERRACE || s == GRASS || s == SHELF || s == DOOR
            || s == CORE || s == PATH || isStair(s);
    }

    /** Cells that occupy their volume with solid matter (block movement and light). */
    public static boolean isSolid(int s) {
        return s == WALL || s == TERRAIN || s == ROOF || s == SUPPORT || s == TRUNK || s == WINDOW
            || s == ARCH || s == GLASS || s == COLUMN || s == WATER;
    }

    /** Cells that are open air (exterior or interior or reserved). */
    public static boolean isOpen(int s) {
        return s == EMPTY || s == AIR || s == KEEP;
    }

    /** Cells that can carry load from above for the support heuristic. */
    public static boolean isLoadBearing(int s) {
        return s == WALL || s == TERRAIN || s == SUPPORT || s == COLUMN || s == TRUNK || s == CORE
            || s == WINDOW || s == ARCH || s == ROOF;
    }

    public static boolean isTemporary(int s) {
        return s >= MARK_A && s <= MARK_D;
    }
}
