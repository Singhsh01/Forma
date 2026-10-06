package studio.forma.engine.core;

import java.util.Arrays;

/**
 * A dense 2D or 3D grid of cell states.
 *
 * <p>Coordinate convention (shared with the web viewer): x points east, y points up,
 * z points south. A 2D grid is simply a grid with {@code sy == 1}. Linear index is
 * {@code x + sx * (z + sz * y)}, so horizontal layers are contiguous.
 *
 * <p>Every cell also carries a component id (which architectural element it belongs to,
 * see {@link Components}). Writes go through {@link #set} so that an attached
 * {@link ChangeSink} (the generation history) observes every state change.
 */
public final class Grid {
    public final int sx, sy, sz;
    private final byte[] state;
    private final short[] comp;
    private ChangeSink sink;

    public interface ChangeSink {
        void changed(int index, byte newState);
    }

    public Grid(int sx, int sy, int sz) {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("grid dimensions must be positive");
        long n = (long) sx * sy * sz;
        if (n > 4_000_000) throw new IllegalArgumentException("grid too large: " + n + " cells (limit 4,000,000)");
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.state = new byte[(int) n];
        this.comp = new short[(int) n];
    }

    public static Grid of2D(int sx, int sz) {
        return new Grid(sx, 1, sz);
    }

    public boolean is2D() {
        return sy == 1;
    }

    public int size() {
        return state.length;
    }

    public void setSink(ChangeSink sink) {
        this.sink = sink;
    }

    public ChangeSink sink() {
        return sink;
    }

    public int index(int x, int y, int z) {
        return x + sx * (z + sz * y);
    }

    public int xOf(int i) {
        return i % sx;
    }

    public int zOf(int i) {
        return (i / sx) % sz;
    }

    public int yOf(int i) {
        return i / (sx * sz);
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    /** Out-of-bounds reads return {@link Cell#EMPTY}. */
    public byte get(int x, int y, int z) {
        if (!inBounds(x, y, z)) return Cell.EMPTY;
        return state[index(x, y, z)];
    }

    public byte getIndex(int i) {
        return state[i];
    }

    public short comp(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0;
        return comp[index(x, y, z)];
    }

    public short compIndex(int i) {
        return comp[i];
    }

    /** Writes a state; out-of-bounds writes are ignored (explicit clipping behaviour). */
    public void set(int x, int y, int z, byte s) {
        if (!inBounds(x, y, z)) return;
        setIndex(index(x, y, z), s);
    }

    public void set(int x, int y, int z, byte s, int component) {
        if (!inBounds(x, y, z)) return;
        int i = index(x, y, z);
        comp[i] = (short) component;
        setIndex(i, s);
    }

    public void setIndex(int i, byte s) {
        if (state[i] == s) return;
        state[i] = s;
        if (sink != null) sink.changed(i, s);
    }

    public void setComp(int i, int component) {
        comp[i] = (short) component;
    }

    public int count(byte s) {
        int c = 0;
        for (byte b : state) if (b == s) c++;
        return c;
    }

    public int[] histogram() {
        int[] h = new int[Cell.COUNT];
        for (byte b : state) h[b & 0xff]++;
        return h;
    }

    /** A detached copy (no sink attached). */
    public Grid copy() {
        Grid g = new Grid(sx, sy, sz);
        System.arraycopy(state, 0, g.state, 0, state.length);
        System.arraycopy(comp, 0, g.comp, 0, comp.length);
        return g;
    }

    public byte[] rawStates() {
        return Arrays.copyOf(state, state.length);
    }

    /** Direct read-only view for hot loops in matchers; callers must never write to it. */
    public byte[] statesView() {
        return state;
    }

    public long fingerprint() {
        long h = 1125899906842597L;
        for (byte b : state) h = 31 * h + b;
        return h ^ ((long) sx << 40) ^ ((long) sy << 20) ^ sz;
    }

    /** Renders one horizontal layer as text using the default legend (handy in tests and docs). */
    public String layerString(int y) {
        StringBuilder sb = new StringBuilder();
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) sb.append(Cell.symbol(get(x, y, z)));
            if (z < sz - 1) sb.append('/');
        }
        return sb.toString();
    }
}
