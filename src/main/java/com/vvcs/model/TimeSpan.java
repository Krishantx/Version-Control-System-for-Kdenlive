package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A length in frames together with the timebase it is counted in.
 *
 * <p>Used wherever a duration is only meaningful relative to a specific frame rate,
 * for example an asset whose media runs at a different rate than the project.
 */
public record TimeSpan(long frames, Rational timebase) {

    public TimeSpan {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative: " + frames);
        }
    }

    public static TimeSpan of(long frames, Rational timebase) {
        return new TimeSpan(frames, timebase);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return frames == 0;
    }

    @JsonIgnore
    public java.math.BigDecimal toSeconds(int scale) {
        return java.math.BigDecimal.valueOf(frames)
                .multiply(java.math.BigDecimal.valueOf(timebase.denominator()))
                .divide(java.math.BigDecimal.valueOf(timebase.numerator()), scale,
                        java.math.RoundingMode.HALF_UP);
    }
}
