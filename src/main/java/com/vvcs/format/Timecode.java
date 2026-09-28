package com.vvcs.format;

import com.vvcs.model.Rational;

/**
 * Renders frame numbers as timecode, for display only.
 *
 * <h2>This is presentation, not data</h2>
 *
 * <p>VVCS stores and compares positions as frame numbers, and that is the truth: a frame is
 * exact, whereas a timecode string is a rendering of one that depends on the frame rate and
 * on a rounding decision. Nothing in the domain model should hold a timecode string, and
 * nothing here should ever replace a frame number. A consumer that needs to display a
 * position calls in here with the appropriate rate; a consumer that needs to compare
 * positions uses the frames.
 *
 * <h2>Two coordinate systems, two rates</h2>
 *
 * <p>A timeline position is in project frames, so it is rendered at the project's frame rate.
 * A source range is in the source asset's own frames, so it is rendered at the asset's
 * timebase. Passing the wrong rate produces a plausible but wrong number, so the rate is an
 * argument everywhere rather than something stored alongside the frames.
 *
 * <h2>Fractional rates</h2>
 *
 * <p>Frame rates such as 24000/1001 are not integers, and this class never pretends they
 * are. {@link #timecode} divides through the exact rational and is correct for any rate.
 * {@link #frames} produces the {@code HH:MM:SS:FF} form, whose last field is only
 * meaningful at a whole number of frames per second; at a fractional rate it reports the
 * frame index within the elapsed second, which is the convention Kdenlive and MLT use and
 * which is why {@link #timecode} is the safer default.
 */
public final class Timecode {

    private Timecode() {
    }

    /**
     * Renders a frame position as {@code HH:MM:SS.mmm}, correct for any frame rate.
     *
     * @param frame     a frame position, in the timebase of {@code frameRate}
     * @param frameRate the rate of that timebase
     */
    public static String timecode(long frame, Rational frameRate) {
        // A position in a zero-rate timebase cannot be rendered. Returning zero rather than
        // throwing keeps a display path from masking the caller's real error, and the model
        // that carried such a rate is wrong for reasons well before any timecode.
        if (frameRate.numerator() == 0) {
            return "00:00:00.000";
        }
        long totalMillis = Math.round(exactSeconds(frame, frameRate) * 1_000d);
        long totalSeconds = Math.floorDiv(totalMillis, 1_000L);
        return String.format("%02d:%02d:%02d.%03d",
                Math.floorDiv(totalSeconds, 3_600L),
                Math.floorDiv(totalSeconds, 60L) % 60L,
                totalSeconds % 60L,
                Math.floorMod(totalMillis, 1_000L));
    }

    /**
     * Renders a frame position as {@code HH:MM:SS:FF}.
     *
     * <p>The seconds and the frame field are derived from the frame count together rather
     * than separately, so the two can never disagree: frame 45 of a 30fps timebase is
     * {@code 00:00:01:15}, not a second field rounded up to 2 beside a frame field of 15.
     *
     * <p>See the note on fractional rates. At a whole number of frames per second this is
     * exact and agrees with {@link #timecode}. At a fractional rate the seconds are counted
     * at the nominal rate, so the two forms can differ by a second; {@link #timecode} is the
     * one to trust there.
     */
    public static String frames(long frame, Rational frameRate) {
        long fps = nominalFps(frameRate);
        long wholeSeconds = Math.floorDiv(frame, fps);
        long framesWithinSecond = Math.floorMod(frame, fps);
        return String.format("%02d:%02d:%02d:%02d",
                Math.floorDiv(wholeSeconds, 3_600L),
                Math.floorDiv(wholeSeconds, 60L) % 60L,
                wholeSeconds % 60L,
                framesWithinSecond);
    }

    /**
     * The frames per second a rate is treated as when a whole number is needed, which is
     * 24000/1001 counted as 24.
     */
    public static long nominalFps(Rational frameRate) {
        return Math.max(1L, Math.round(frameRate.toDouble()));
    }

    private static double exactSeconds(long frame, Rational frameRate) {
        return (double) frame * frameRate.denominator() / frameRate.numerator();
    }
}
