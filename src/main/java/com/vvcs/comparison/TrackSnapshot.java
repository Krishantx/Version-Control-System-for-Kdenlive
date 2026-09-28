package com.vvcs.comparison;

import com.vvcs.model.TrackType;

import java.util.List;

/**
 * One track, flattened for drawing.
 *
 * <p>{@code clips} is in timeline order, which is the order an editor sees them in and the
 * order a timeline is drawn left to right. A track's own {@code index} is not stored: its
 * position in the enclosing {@link TimelineSnapshot}'s track list is its position.
 */
public record TrackSnapshot(
        String id,
        TrackType type,
        int index,
        List<ClipSnapshot> clips) {

    public TrackSnapshot {
        clips = clips == null ? List.of() : List.copyOf(clips);
    }

    /** The frame after the last one occupied by this track's clips. */
    public long end() {
        return clips.stream().mapToLong(ClipSnapshot::end).max().orElse(0L);
    }

    public int clipCount() {
        return clips.size();
    }
}
