package com.vvcs.diff;

import java.util.List;

/**
 * The complete, ordered result of comparing two states.
 *
 * <p>This is a result, not a report. It holds structured facts and knows nothing about
 * Kdenlive, XML, JSON, a repository layout, or how output should be laid out; formatting for
 * a terminal is somebody else's job.
 *
 * @param fromStateId identity of the older state, when the caller has one
 * @param toStateId   identity of the newer state, when the caller has one
 * @param assetChanges assets added, removed, or changed
 * @param trackChanges tracks added, removed, or changed
 * @param clipChanges  clips added, removed, moved, trimmed, reordered, or moved between tracks
 * @param fromDuration total timeline length in frames before the change
 * @param toDuration   total timeline length in frames after the change
 */
public record TimelineDiff(
        String fromStateId,
        String toStateId,
        List<AssetChange> assetChanges,
        List<TrackChange> trackChanges,
        List<ClipChange> clipChanges,
        long fromDuration,
        long toDuration) {

    public TimelineDiff {
        assetChanges = assetChanges == null ? List.of() : List.copyOf(assetChanges);
        trackChanges = trackChanges == null ? List.of() : List.copyOf(trackChanges);
        clipChanges = clipChanges == null ? List.of() : List.copyOf(clipChanges);
    }

    /**
     * True when nothing at all changed.
     *
     * <p>Excluded from the JSON: it is a question about the three change lists that a consumer
     * can answer for itself, and a stored answer can disagree with the lists beside it.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return assetChanges.isEmpty() && trackChanges.isEmpty() && clipChanges.isEmpty()
                && fromDuration == toDuration;
    }
}
