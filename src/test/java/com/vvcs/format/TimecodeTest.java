package com.vvcs.format;

import com.vvcs.model.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timecode rendering, which exists for display and is never allowed to become a coordinate.
 *
 * <p>The cases that matter are the awkward ones: rates that are not whole numbers, and frames
 * that land on a second boundary. A helper that is wrong only at 23.976fps is worse than none,
 * because it looks right on the projects that were easy to make.
 */
class TimecodeTest {

    private static final Rational TWENTY_FIVE = new Rational(25, 1);
    private static final Rational THIRTY = new Rational(30, 1);
    private static final Rational FILM = new Rational(24, 1);
    private static final Rational NTSC = new Rational(24_000, 1_001);

    @Test
    @DisplayName("whole rates render zero-padded HH:MM:SS.mmm")
    void wholeRates() {
        assertAll(
                () -> assertEquals("00:00:00.000", Timecode.timecode(0, THIRTY)),
                () -> assertEquals("00:00:01.000", Timecode.timecode(30, THIRTY)),
                () -> assertEquals("00:01:00.000", Timecode.timecode(1_800, THIRTY)),
                // 900 frames at 25fps is 36 seconds, not 30: 30 would be the answer at 30fps
                () -> assertEquals("00:00:36.000", Timecode.timecode(900, TWENTY_FIVE)),
                () -> assertEquals("00:05:00.000", Timecode.timecode(9_000, THIRTY)),
                // An hour is 108000 frames at 30fps
                () -> assertEquals("01:00:00.000", Timecode.timecode(108_000, THIRTY)));
    }

    @Test
    @DisplayName("the frame count of a second is honoured, so 25fps is not 30fps")
    void respectsTheRate() {
        // One second is a different number of frames at each rate, and getting this wrong
        // shifts every position in a project by a growing margin.
        assertAll(
                () -> assertEquals("00:00:01.000", Timecode.timecode(24, FILM)),
                () -> assertEquals("00:00:01.000", Timecode.timecode(25, TWENTY_FIVE)),
                () -> assertEquals("00:00:01.000", Timecode.timecode(30, THIRTY)),
                // The same 30 frames is a different length at a different rate
                () -> assertEquals("00:00:01.200", Timecode.timecode(30, TWENTY_FIVE)),
                () -> assertEquals("00:00:00.833", Timecode.timecode(25, THIRTY)));
    }

    @Test
    @DisplayName("a fractional rate is divided through exactly, not rounded to a whole rate")
    void fractionalRates() {
        // 24000/1001 is 23.976, not 24. After 24000 frames the difference has grown to a
        // whole second: treating it as 24fps would place this at 00:16:40 when it is 00:16:41.
        assertAll(
                // 24 frames is one second at 24fps, and a thousandth more at 23.976
                () -> assertEquals("00:00:01.001", Timecode.timecode(24, NTSC)),
                () -> assertEquals("00:00:01.000", Timecode.timecode(24, FILM)),
                () -> assertEquals("00:00:00.800", Timecode.timecode(24, THIRTY)),
                () -> assertEquals("00:16:41.000", Timecode.timecode(24_000, NTSC)),
                // The same frame count at 24fps lands a second earlier, which is the error
                () -> assertEquals("00:16:40.000", Timecode.timecode(24_000, FILM)));
    }

    @Test
    @DisplayName("milliseconds never round up into the next second")
    void noMillisecondOverflow() {
        // 0.9995s must not render as 1.000, which would show a frame before the second it
        // belongs to.
        for (long frame = 0; frame < 3_000; frame++) {
            String rendered = Timecode.timecode(frame, NTSC);
            long millis = Long.parseLong(rendered.substring(rendered.length() - 3));
            assertTrue(millis >= 0 && millis < 1_000, "bad milliseconds: " + rendered);
        }
    }

    @Test
    @DisplayName("the frame form reports frames within the second")
    void frameForm() {
        assertAll(
                () -> assertEquals("00:00:00:00", Timecode.frames(0, THIRTY)),
                () -> assertEquals("00:00:01:00", Timecode.frames(30, THIRTY)),
                // Frame 45 of 30fps is one and a half seconds, so the seconds must read 1
                // beside the frame field. Rounding the two independently would read 2.
                () -> assertEquals("00:00:01:15", Timecode.frames(45, THIRTY)),
                () -> assertEquals("00:01:00:00", Timecode.frames(1_800, THIRTY)),
                // An hour of 30fps is 108000 frames, not 3600
                () -> assertEquals("01:00:00:00", Timecode.frames(108_000, THIRTY)),
                () -> assertEquals("00:00:01:00", Timecode.frames(25, TWENTY_FIVE)));
    }

    @Test
    @DisplayName("a fractional rate is treated as 24 for the frame field and documented as such")
    void fractionalFrameForm() {
        // 24000 frames of 23.976 is 1001 seconds; counted at the nominal 24fps the same frames
        // are 1000. So after sixteen and a half minutes the two forms differ by a second,
        // which is the documented cost of the frame form here, and why timecode() is the one
        // to trust at a fractional rate.
        assertAll(
                () -> assertEquals(24, Timecode.nominalFps(NTSC)),
                () -> assertEquals(25, Timecode.nominalFps(TWENTY_FIVE)),
                () -> assertEquals("00:16:40:00", Timecode.frames(24_000, NTSC)),
                () -> assertEquals("00:16:41.000", Timecode.timecode(24_000, NTSC)),
                // One second in they still agree, to within the millisecond
                () -> assertEquals("00:00:01:00", Timecode.frames(24, NTSC)),
                () -> assertEquals("00:00:01.001", Timecode.timecode(24, NTSC)),
                () -> assertEquals("00:16:40:00", Timecode.frames(24_000, FILM)),
                () -> assertEquals("00:16:40.000", Timecode.timecode(24_000, FILM)));
    }


    @Test
    @DisplayName("an unreasonable rate cannot divide by zero")
    void degenerateRates() {
        // A zero rate is nonsense, but a display helper should not turn one into an
        // ArithmeticException that loses the error a caller was about to report.
        assertAll(
                () -> assertEquals("00:00:00.000", Timecode.timecode(100, Rational.ZERO)),
                () -> assertEquals(1, Timecode.nominalFps(Rational.ZERO)));
    }

    @Test
    @DisplayName("the parser's own formatting still works, and agrees")
    void parserDelegates() {
        // KdenliveTimeParser keeps its method for its existing callers; both paths must render
        // identically or a project would be reported at two different positions.
        assertAll(
                () -> assertEquals(com.vvcs.parser.KdenliveTimeParser.formatTimecode(45, THIRTY),
                        Timecode.timecode(45, THIRTY)),
                () -> assertEquals("00:00:01.500", Timecode.timecode(45, THIRTY)));
    }
}
