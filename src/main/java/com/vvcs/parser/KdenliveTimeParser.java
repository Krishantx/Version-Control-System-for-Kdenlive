package com.vvcs.parser;

import com.vvcs.model.Rational;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between the {@code HH:MM:SS.mmm} position strings that MLT/Kdenlive write and
 * integer frame numbers.
 *
 * <p>Kdenlive stores every {@code in}, {@code out} and {@code length} as a millisecond
 * timecode rendered against the <em>project</em> timebase, so a position string is only
 * meaningful together with the frame rate it is read at. All conversions here are integer
 * arithmetic: a millisecond value is mapped to the nearest frame and no float ever enters
 * the calculation.
 */
public final class KdenliveTimeParser {

    private static final Pattern TIMECODE =
            Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})\\.(\\d{1,3})$");

    private KdenliveTimeParser() {
    }

    /**
     * Parses an MLT timecode into a frame number in the given timebase.
     *
     * @throws KdenliveParserException if the value is not a timecode MLT could have written
     */
    public static long parseFrames(String timecode, Rational frameRate) {
        if (timecode == null || timecode.isBlank()) {
            throw new KdenliveParserException(KdenliveParserException.Reason.MALFORMED_XML,
                    "missing timecode value");
        }
        Matcher matcher = TIMECODE.matcher(timecode.trim());
        if (!matcher.matches()) {
            throw new KdenliveParserException(KdenliveParserException.Reason.MALFORMED_XML,
                    "unrecognised MLT timecode: '" + timecode + "'");
        }
        long hours = group(matcher, 1);
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        long millis = Long.parseLong((matcher.group(4) + "00").substring(0, 3));

        long totalMillis = Math.addExact(
                Math.addExact(Math.addExact(Math.multiplyExact(hours, 3_600_000L),
                        Math.multiplyExact(minutes, 60_000L)),
                Math.multiplyExact(seconds, 1_000L)),
                millis);

        return millisToFrames(totalMillis, frameRate);
    }

    /**
     * Renders a frame number as a non-drop {@code HH:MM:SS.mmm} timecode.
     *
     * <p>Delegated to {@link com.vvcs.format.Timecode}, which is where timecode rendering
     * lives: parsing a timecode is this class's business, producing one is not.
     */
    public static String formatTimecode(long frame, Rational frameRate) {
        return com.vvcs.format.Timecode.timecode(frame, frameRate);
    }

    /** Rounds a millisecond position to the nearest whole frame in the given timebase. */
    public static long millisToFrames(long millis, Rational frameRate) {
        return divRoundHalfUp(
                Math.multiplyExact(millis, frameRate.numerator()),
                Math.multiplyExact(1_000L, frameRate.denominator()));
    }

    /** Rounds a frame number to the nearest whole millisecond, for display only. */
    public static long framesToMillis(long frame, Rational frameRate) {
        return divRoundHalfUp(
                Math.multiplyExact(frame, frameRate.denominator()),
                Math.multiplyExact(1_000L, frameRate.numerator()));
    }

    private static long divRoundHalfUp(long dividend, long divisor) {
        long half = Math.floorDiv(divisor, 2);
        return dividend >= 0
                ? Math.floorDiv(dividend + half, divisor)
                : -Math.floorDiv(-dividend + half, divisor);
    }

    private static long group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? 0L : Long.parseLong(value);
    }
}
