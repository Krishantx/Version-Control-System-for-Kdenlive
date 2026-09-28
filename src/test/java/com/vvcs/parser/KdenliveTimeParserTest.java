package com.vvcs.parser;

import com.vvcs.model.Rational;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KdenliveTimeParserTest {

    private static final Rational FILM = new Rational(24000, 1001);
    private static final Rational TWENTY_FIVE = new Rational(25, 1);
    private static final Rational THIRTY = new Rational(30, 1);

    @Test
    void readsPositionsOfTheRealProject() {
        assertEquals(803L, KdenliveTimeParser.parseFrames("00:00:33.492", FILM));
        assertEquals(256L, KdenliveTimeParser.parseFrames("00:00:10.677", FILM));
        assertEquals(747L, KdenliveTimeParser.parseFrames("00:00:31.156", FILM));
        assertEquals(1090L, KdenliveTimeParser.parseFrames("00:00:45.462", FILM));
        assertEquals(0L, KdenliveTimeParser.parseFrames("00:00:00.000", FILM));
    }

    @Test
    void readsPositionsWithHoursAndShortFractions() {
        assertEquals(3_600L * 25, KdenliveTimeParser.parseFrames("01:00:00.000", TWENTY_FIVE));
        assertEquals(25L, KdenliveTimeParser.parseFrames("00:00:01.0", TWENTY_FIVE));
        assertEquals(1L, KdenliveTimeParser.parseFrames("00:00:00.04", TWENTY_FIVE));
        assertEquals(1_500L, KdenliveTimeParser.parseFrames("00:01:00.000", TWENTY_FIVE));
    }

    @Test
    void writesBackTheSameTimecodeItRead() {
        for (String timecode : new String[]{
                "00:00:00.000", "00:00:33.492", "00:00:10.677", "00:00:31.156", "00:00:45.462"}) {
            long frame = KdenliveTimeParser.parseFrames(timecode, FILM);
            assertEquals(timecode, KdenliveTimeParser.formatTimecode(frame, FILM),
                    "round trip of " + timecode);
        }
    }

    @Test
    void formatsWholeSecondsExactly() {
        assertEquals("00:00:01.000", KdenliveTimeParser.formatTimecode(25, TWENTY_FIVE));
        assertEquals("00:01:00.000", KdenliveTimeParser.formatTimecode(1_500, TWENTY_FIVE));
        assertEquals("00:00:30.000", KdenliveTimeParser.formatTimecode(900, THIRTY));
    }

    @Test
    void rejectsValuesThatAreNotTimecodes() {
        assertThrows(KdenliveParserException.class, () -> KdenliveTimeParser.parseFrames("", FILM));
        assertThrows(KdenliveParserException.class, () -> KdenliveTimeParser.parseFrames(null, FILM));
        assertThrows(KdenliveParserException.class,
                () -> KdenliveTimeParser.parseFrames("33.492", FILM));
        assertThrows(KdenliveParserException.class,
                () -> KdenliveTimeParser.parseFrames("00:00:xx", FILM));
    }

    @Test
    void convertsBetweenTimebasesWithoutFloatingPoint() {
        assertEquals(1_005L, FILM.scaleTo(804L, new Rational(30000, 1001)));
        assertEquals(643L, FILM.scaleTo(257L, new Rational(60000, 1001)));
        assertEquals(780L, FILM.scaleTo(748L, TWENTY_FIVE));
        assertEquals(1_091L, FILM.scaleTo(1_091L, FILM));
        assertEquals(804L, new Rational(30000, 1001).scaleTo(1_005L, FILM));
    }

    @Test
    void normalisesRationals() {
        assertEquals(new Rational(24_000, 1_001), new Rational(48_000, 2_002));
        assertEquals(new Rational(1, 2), new Rational(-2, -4));
        assertEquals(new Rational(0, 1), new Rational(0, 7));
        assertEquals(0, new Rational(0, 7).numerator());
        assertEquals(1, new Rational(0, 7).denominator());
        assertEquals(BigDecimal.valueOf(24), new Rational(24, 1).toBigDecimal(0));
    }
}
