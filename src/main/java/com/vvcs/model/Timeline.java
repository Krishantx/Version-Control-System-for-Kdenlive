package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * The edit itself: an ordered set of tracks sharing one project timebase, plus the
 * project-level frame rate and resolution they are timed against.
 */
public record Timeline(
        String id,
        String name,
        Rational frameRate,
        Resolution resolution,
        long duration,
        List<Track> tracks) {

    public Timeline {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("timeline id must be set");
        }
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        if (duration < 0) {
            throw new IllegalArgumentException("timeline duration must not be negative: " + duration);
        }
    }

    @JsonIgnore
    public List<Clip> clips() {
        return tracks.stream().flatMap(t -> t.clips().stream()).toList();
    }

    @JsonIgnore
    public int clipCount() {
        return tracks.stream().mapToInt(Track::clipCount).sum();
    }

    @JsonIgnore
    public List<Track> tracksOfType(TrackType type) {
        return tracks.stream().filter(t -> t.type() == type).toList();
    }

    @JsonIgnore
    public Track track(String trackId) {
        return tracks.stream()
                .filter(t -> t.id().equals(trackId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such track: " + trackId));
    }
}
