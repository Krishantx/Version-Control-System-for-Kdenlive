package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One source media file known to the project.
 *
 * <p>An asset is the media itself, independent of how often it is placed on the timeline.
 * Two or more project-level references to the same file resolve to a single asset, so the
 * identity of an asset is the media, never a placement.
 */
public record Asset(
        String id,
        String name,
        String path,
        String hash,
        TimeSpan duration,
        Resolution resolution,
        String videoCodec,
        String audioCodec) {

    public Asset {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("asset id must be set");
        }
    }

    @JsonIgnore
    public boolean hasAudio() {
        return audioCodec != null && !audioCodec.isBlank();
    }

    @JsonIgnore
    public boolean hasVideo() {
        return videoCodec != null && !videoCodec.isBlank();
    }

    @JsonIgnore
    public Rational frameRate() {
        return duration.timebase();
    }
}
