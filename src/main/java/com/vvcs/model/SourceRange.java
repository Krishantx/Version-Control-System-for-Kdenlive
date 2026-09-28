package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The part of a source asset that a single clip plays, expressed in the asset's own
 * timebase. This is deliberately separate from the clip's {@link Clip#timeline()}
 * placement, which is expressed in the project timebase.
 */
public record SourceRange(
        @JsonProperty("in") long inFrame,
        @JsonProperty("out") long outFrame,
        long duration,
        Rational timebase) {

    public SourceRange {
        if (inFrame < 0) {
            throw new IllegalArgumentException("source in frame must not be negative: " + inFrame);
        }
        if (outFrame < inFrame - 1) {
            throw new IllegalArgumentException("source out frame " + outFrame + " precedes in frame " + inFrame);
        }
        if (duration < 0) {
            throw new IllegalArgumentException("source duration must not be negative: " + duration);
        }
    }

    public static SourceRange of(TimeSpan fullSpan) {
        return new SourceRange(0, Math.max(0, fullSpan.frames() - 1), fullSpan.frames(), fullSpan.timebase());
    }

    @JsonIgnore
    public TimeSpan span() {
        return new TimeSpan(duration, timebase);
    }

    @JsonIgnore
    public TimeRange frameRange() {
        return TimeRange.ofInclusives(inFrame, outFrame);
    }
}
