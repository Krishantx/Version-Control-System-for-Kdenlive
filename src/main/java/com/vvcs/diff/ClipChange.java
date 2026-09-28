package com.vvcs.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Set;

/**
 * One clip as it was and as it became.
 *
 * <p>A change carries both sides rather than a list of separate field deltas, so a caller
 * can always see the before and the after. {@code before} is null for {@link ChangeType#ADDED}
 * and {@code after} is null for {@link ChangeType#REMOVED}.
 *
 * <p>{@code kinds} holds every type that applies, not only the dominant one. A clip that
 * moved and was trimmed at the same time is one change that is both {@code MOVED} and
 * {@code TRIMMED}; reporting it as two changes, or as one vague {@code MODIFIED}, would
 * lose information that a version control system exists to provide.
 */
public record ClipChange(
        String key,
        ChangeType type,
        Set<ChangeType> kinds,
        ClipOccurrence before,
        ClipOccurrence after) {

    /**
     * Freezes {@code kinds} into an immutable set that keeps the order it arrived in.
     *
     * <p>The order is the engine's, most significant kind first, and it is kept rather than
     * normalised for two reasons. It is what a reader expects to see first, and
     * {@link Set#copyOf} does not keep it: it hashes into a set whose iteration order is
     * deliberately randomised per run, so serializing the kinds of one change could produce
     * {@code ["MOVED","TRIMMED"]} on one run and {@code ["TRIMMED","MOVED"]} on the next. A
     * diff that a frontend has to re-diff to make sense of a timeline is also a diff that
     * has to be byte for byte reproducible to be diffable itself.
     */
    public ClipChange {
        if (kinds == null || kinds.isEmpty()) {
            kinds = Set.of();
        } else {
            kinds = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(kinds));
        }
    }

    public static ClipChange added(ClipOccurrence after) {
        return new ClipChange(after.key(), ChangeType.ADDED, Set.of(ChangeType.ADDED), null, after);
    }

    public static ClipChange removed(ClipOccurrence before) {
        return new ClipChange(before.key(), ChangeType.REMOVED, Set.of(ChangeType.REMOVED), before, null);
    }

    /**
     * The stable key, which is the same on both sides. Useful for reporting a change without
     * depending on either side's positional id.
     */
    public String clipId() {
        return after != null ? after.clipId() : before.clipId();
    }

    // -------------------------------------------------------------------------------------
    // Flat views of the two sides.
    //
    // Convenience for reading a change without unpacking it, not a second copy of the facts.
    // Each is derived from before/after, which remain the only place the data is stored, and
    // each is excluded from the JSON so that a consumer cannot receive two representations of
    // the same value that could disagree. Which side is absent depends on the change: an
    // addition has no before, a removal has no after.
    // -------------------------------------------------------------------------------------

    /** The track the clip was on, or null for an addition. */
    @JsonIgnore
    public String oldTrackId() {
        return before == null ? null : before.trackId();
    }

    /** The track the clip is on, or null for a removal. */
    @JsonIgnore
    public String newTrackId() {
        return after == null ? null : after.trackId();
    }

    /** The first timeline frame the clip occupied, in project frames, or null if added. */
    @JsonIgnore
    public Long oldStart() {
        return before == null ? null : before.timelineStart();
    }

    /** The first timeline frame the clip occupies, in project frames, or null if removed. */
    @JsonIgnore
    public Long newStart() {
        return after == null ? null : after.timelineStart();
    }

    /** The clip's length in project frames, or null if added. */
    @JsonIgnore
    public Long oldDuration() {
        return before == null ? null : before.timelineDuration();
    }

    /** The clip's length in project frames, or null if removed. */
    @JsonIgnore
    public Long newDuration() {
        return after == null ? null : after.timelineDuration();
    }

    /** The first source frame used, in the asset's own frames, or null if added. */
    @JsonIgnore
    public Long oldSourceIn() {
        return before == null ? null : before.sourceIn();
    }

    /** The first source frame used, in the asset's own frames, or null if removed. */
    @JsonIgnore
    public Long newSourceIn() {
        return after == null ? null : after.sourceIn();
    }

    /** The last source frame used, inclusive, or null if added. */
    @JsonIgnore
    public Long oldSourceOut() {
        return before == null ? null : before.sourceOut();
    }

    /** The last source frame used, inclusive, or null if removed. */
    @JsonIgnore
    public Long newSourceOut() {
        return after == null ? null : after.sourceOut();
    }

    /** The clip's position among its track's clips before the change, or null if added. */
    @JsonIgnore
    public Integer oldOrder() {
        return before == null ? null : before.orderOnTrack();
    }

    /** The clip's position among its track's clips after the change, or null if removed. */
    @JsonIgnore
    public Integer newOrder() {
        return after == null ? null : after.orderOnTrack();
    }

    /** The media the clip played before the change, or null if added. */
    @JsonIgnore
    public String oldAssetId() {
        return before == null ? null : before.assetId();
    }

    /** The media the clip plays now, or null if removed. */
    @JsonIgnore
    public String newAssetId() {
        return after == null ? null : after.assetId();
    }
}
