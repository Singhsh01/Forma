package studio.forma.engine.rules;

/**
 * Supported symmetry groups for rule variants.
 *
 * <p>FORMA works with gravity: patterns may be rotated about the vertical (y) axis and mirrored
 * horizontally, but never turned upside down. This is a deliberate subset of MarkovJunior's
 * cube symmetries, which also allow rotations that swap "up" with a horizontal axis.
 */
public enum Symmetry {
    /** Pattern used exactly as written. */
    NONE,
    /** Identity plus mirror across x. */
    MIRROR_X,
    /** The four quarter-turn rotations about y. */
    ROTATE_Y,
    /** Rotations about y combined with mirroring (the dihedral group of order 8). */
    FULL;

    public static Symmetry parse(String s) {
        return switch (s.trim().toLowerCase()) {
            case "none", "" -> NONE;
            case "mirror", "mirror_x", "mirrorx" -> MIRROR_X;
            case "rotate", "rotate_y", "rot" -> ROTATE_Y;
            case "full", "all" -> FULL;
            default -> throw new RuleValidationException("unknown symmetry '" + s + "' (use none, mirror, rotate, full)");
        };
    }
}
