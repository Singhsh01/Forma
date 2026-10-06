package studio.forma.engine.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An input/output pattern pair of identical size.
 *
 * <p>Text syntax: characters run along x, {@code /} separates rows along z, and a space
 * separates horizontal layers along y, listed bottom to top. Example: {@code "G ."} is a
 * two-layer column (grass with air above). Inputs hold state sets as bitmasks
 * ({@code -1} = wildcard); outputs hold a state or {@code -1} for "leave unchanged".
 */
public final class Pattern {
    public final int nx, ny, nz;
    final int[] in;
    final byte[] out;
    /** offsets (dx,dy,dz) of non-wildcard input cells, most selective first */
    final int[] checkOffsets;
    final int[] checkMasks;

    public Pattern(int nx, int ny, int nz, int[] in, byte[] out) {
        if (in.length != nx * ny * nz || out.length != in.length)
            throw new RuleValidationException("pattern arrays do not match dimensions " + nx + "x" + ny + "x" + nz);
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.in = in;
        this.out = out;
        List<int[]> checks = new ArrayList<>();
        for (int y = 0; y < ny; y++)
            for (int z = 0; z < nz; z++)
                for (int x = 0; x < nx; x++) {
                    int m = in[idx(x, y, z)];
                    if (m != -1) checks.add(new int[]{x, y, z, m, Integer.bitCount(m)});
                }
        checks.sort((a, b) -> Integer.compare(a[4], b[4]));
        checkOffsets = new int[checks.size() * 3];
        checkMasks = new int[checks.size()];
        for (int i = 0; i < checks.size(); i++) {
            int[] c = checks.get(i);
            checkOffsets[i * 3] = c[0];
            checkOffsets[i * 3 + 1] = c[1];
            checkOffsets[i * 3 + 2] = c[2];
            checkMasks[i] = c[3];
        }
    }

    int idx(int x, int y, int z) {
        return x + nx * (z + nz * y);
    }

    public int inputAt(int x, int y, int z) {
        return in[idx(x, y, z)];
    }

    public int outputAt(int x, int y, int z) {
        return out[idx(x, y, z)];
    }

    public int volume() {
        return in.length;
    }

    public static Pattern parse(String input, String output, Legend legend) {
        int[] dimsIn = dims(input, "input");
        int[] dimsOut = dims(output, "output");
        if (!Arrays.equals(dimsIn, dimsOut))
            throw new RuleValidationException("input " + fmt(dimsIn) + " and output " + fmt(dimsOut)
                + " must have the same size (\"" + input + "\" -> \"" + output + "\")");
        int nx = dimsIn[0], ny = dimsIn[1], nz = dimsIn[2];
        int[] in = new int[nx * ny * nz];
        byte[] out = new byte[nx * ny * nz];
        String[] layersIn = input.trim().split(" +");
        String[] layersOut = output.trim().split(" +");
        for (int y = 0; y < ny; y++) {
            String[] rowsIn = layersIn[y].split("/", -1);
            String[] rowsOut = layersOut[y].split("/", -1);
            for (int z = 0; z < nz; z++)
                for (int x = 0; x < nx; x++) {
                    int i = x + nx * (z + nz * y);
                    in[i] = legend.inputMask(rowsIn[z].charAt(x));
                    out[i] = (byte) legend.output(rowsOut[z].charAt(x));
                }
        }
        boolean changes = false;
        for (int i = 0; i < in.length; i++) if (out[i] != -1) changes = true;
        if (!changes) throw new RuleValidationException("rule \"" + input + "\" -> \"" + output + "\" never changes anything");
        return new Pattern(nx, ny, nz, in, out);
    }

    private static String fmt(int[] d) {
        return d[0] + "x" + d[1] + "x" + d[2];
    }

    private static int[] dims(String s, String what) {
        if (s == null || s.isBlank()) throw new RuleValidationException("empty " + what + " pattern");
        String[] layers = s.trim().split(" +");
        int nz = -1, nx = -1;
        for (String layer : layers) {
            String[] rows = layer.split("/", -1);
            if (nz == -1) nz = rows.length;
            else if (rows.length != nz)
                throw new RuleValidationException(what + " \"" + s + "\": every layer needs " + nz + " rows");
            for (String row : rows) {
                if (row.isEmpty()) throw new RuleValidationException(what + " \"" + s + "\" has an empty row");
                if (nx == -1) nx = row.length();
                else if (row.length() != nx)
                    throw new RuleValidationException(what + " \"" + s + "\": every row needs " + nx + " cells");
            }
        }
        return new int[]{nx, layers.length, nz};
    }

    /** Quarter turn about the vertical axis: (x, z) -> (nz-1-z, x). */
    public Pattern rotatedY() {
        int nnx = nz, nnz = nx;
        int[] ni = new int[in.length];
        byte[] no = new byte[out.length];
        for (int y = 0; y < ny; y++)
            for (int z = 0; z < nz; z++)
                for (int x = 0; x < nx; x++) {
                    int tx = nz - 1 - z, tz = x;
                    int t = tx + nnx * (tz + nnz * y);
                    ni[t] = in[idx(x, y, z)];
                    no[t] = out[idx(x, y, z)];
                }
        return new Pattern(nnx, ny, nnz, ni, no);
    }

    public Pattern mirroredX() {
        int[] ni = new int[in.length];
        byte[] no = new byte[out.length];
        for (int y = 0; y < ny; y++)
            for (int z = 0; z < nz; z++)
                for (int x = 0; x < nx; x++) {
                    int t = (nx - 1 - x) + nx * (z + nz * y);
                    ni[t] = in[idx(x, y, z)];
                    no[t] = out[idx(x, y, z)];
                }
        return new Pattern(nx, ny, nz, ni, no);
    }

    public List<Pattern> variants(Symmetry sym) {
        List<Pattern> base = new ArrayList<>();
        base.add(this);
        if (sym == Symmetry.ROTATE_Y || sym == Symmetry.FULL) {
            Pattern p = this;
            for (int i = 0; i < 3; i++) {
                p = p.rotatedY();
                base.add(p);
            }
        }
        if (sym == Symmetry.MIRROR_X || sym == Symmetry.FULL) {
            int n = base.size();
            for (int i = 0; i < n; i++) base.add(base.get(i).mirroredX());
        }
        List<Pattern> unique = new ArrayList<>();
        for (Pattern p : base) {
            boolean dup = false;
            for (Pattern u : unique) if (u.sameAs(p)) { dup = true; break; }
            if (!dup) unique.add(p);
        }
        return unique;
    }

    public boolean sameAs(Pattern o) {
        return nx == o.nx && ny == o.ny && nz == o.nz && Arrays.equals(in, o.in) && Arrays.equals(out, o.out);
    }
}
