package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A closed range of frames in a single, known timebase.
 *
 * <p>{@code start} is the first frame of the range, {@code duration} its length in frames.
 * The last frame is therefore {@code end() - 1}. All positions are integers; no range is
 * ever expressed in floating point seconds.
 */
public record TimeRange(long start, long duration) {

    public TimeRange {
        if (start < 0) {
            throw new IllegalArgumentException("start frame must not be negative: " + start);
        }
        if (duration < 0) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
    }

    public static TimeRange ofInclusives(long firstFrame, long lastFrame) {
        return new TimeRange(firstFrame, lastFrame - firstFrame + 1);
    }

    public long end() {
        return start + duration;
    }

    public long lastFrame() {
        return end() - 1;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return duration == 0;
    }

    @JsonIgnore
    public TimeRange shift(long frames) {
        return new TimeRange(start + frames, duration);
    }

    @JsonIgnore
    public TimeRange truncateToLength(long newDuration) {
        return new TimeRange(start, Math.min(duration, newDuration));
    }
}
