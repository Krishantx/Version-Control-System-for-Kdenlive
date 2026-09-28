package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * One lane of the timeline. A track owns an ordered list of clip placements; the ordering
 * is the semantic content, the clips themselves carry the exact positions.
 */
public record Track(
        String id,
        TrackType type,
        List<Clip> clips) {

    public Track {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("track id must be set");
        }
        clips = clips == null ? List.of() : List.copyOf(clips);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return clips.isEmpty();
    }

    @JsonIgnore
    public int clipCount() {
        return clips.size();
    }

    @JsonIgnore
    public long endFrame() {
        return clips.stream().mapToLong(c -> c.timeline().end()).max().orElse(0L);
    }
}
