package com.vvcs.diff;

/**
 * What happened to one thing between two states.
 *
 * <p>{@link #MODIFIED} exists for the case where a clip is recognisably the same clip but
 * nothing more specific applies, such as a change of source range that is not a trim because
 * the duration is unchanged. It is deliberately last in {@link #dominant()} so that a
 * combined change is never reported as a vague modification.
 */
public enum ChangeType {
    /** Present in the newer state only. */
    ADDED,
    /** Present in the older state only. */
    REMOVED,
    /** Placed on a different track, with everything else about it recognisable. */
    TRACK_CHANGED,
    /** Kept on the same track but in a different position among its neighbours. */
    REORDERED,
    /** Started at a different point on the timeline. */
    MOVED,
    /** A different length, or a different range of its source. */
    TRIMMED,
    /** Changed, but not in a way any more specific type describes. */
    MODIFIED;

    /**
     * The single most informative type of a set of changes, so that a clip that moved and was
     * trimmed is reported as the more telling of the two rather than as a bare modification.
     */
    public static ChangeType dominant(ChangeType... types) {
        ChangeType best = MODIFIED;
        for (ChangeType type : types) {
            if (type.ordinal() < best.ordinal()) {
                best = type;
            }
        }
        return best;
    }
}
