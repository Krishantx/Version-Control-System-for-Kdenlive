package com.vvcs.comparison;

import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;

import java.util.List;

/**
 * A whole timeline, flattened into everything needed to draw it and to know which state it
 * is.
 *
 * <h2>What a snapshot is for</h2>
 *
 * <p>A diff on its own says what changed, which is not enough to draw anything: a frontend
 * handed only changes would have to know the unchanged parts too. A snapshot is the timeline
 * as it stood, so that a consumer can render before, render after, and then use the changes
 * to annotate the difference, without asking VVCS for anything else.
 *
 * <p>A snapshot carries a project name and a state id because a consumer displaying two
 * side by side needs to label them, and because the pair of state ids is what makes a
 * comparison identifiable.
 *
 * <h2>Frames</h2>
 *
 * <p>{@code duration} is in project timeline frames, counted at {@code frameRate}. The
 * frame rate is a {@link Rational} because it is rarely a whole number, and a consumer that
 * needs seconds or timecode converts using it. No position in this type is pre-converted to
 * any other unit.
 */
public record TimelineSnapshot(
        String stateId,
        String projectName,
        String id,
        String name,
        long duration,
        Rational frameRate,
        Resolution resolution,
        List<TrackSnapshot> tracks) {

    public TimelineSnapshot {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    public int trackCount() {
        return tracks.size();
    }

    public int clipCount() {
        return tracks.stream().mapToInt(TrackSnapshot::clipCount).sum();
    }

    /** The track with this id, or null if this snapshot has no such track. */
    public TrackSnapshot track(String trackId) {
        return tracks.stream()
                .filter(track -> track.id().equals(trackId))
                .findFirst()
                .orElse(null);
    }
}
