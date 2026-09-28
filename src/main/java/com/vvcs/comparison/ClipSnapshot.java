package com.vvcs.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vvcs.model.Rational;

/**
 * One clip, flattened into everything needed to draw a block on a timeline.
 *
 * <h2>Coordinates</h2>
 *
 * <p>{@code start} and {@code duration} are in <em>project timeline frames</em>, the
 * coordinate system every clip on every track shares. {@code sourceIn} and {@code sourceOut}
 * are in the <em>source asset's own frames</em>, at {@code sourceFrameRate}.
 *
 * <p>These are different coordinate systems and this type never converts between them. A
 * consumer that wants to show a source range as a duration must use {@code sourceFrameRate};
 * one that wants to position a block must use the timeline's frame rate. The distinction is
 * carried in the data rather than left to a comment, so that a consumer cannot get it wrong
 * by accident.
 *
 * <h2>Endpoint convention</h2>
 *
 * <p>{@code sourceOut} is inclusive, as Kdenlive's own convention has it: a source range of
 * {@code [0..49]} is fifty frames. {@code start} and {@code duration} are half open, so a
 * clip at start 0 with duration 50 covers frames 0 through 49. The two conventions differ
 * deliberately, and both are what the underlying model already used.
 *
 * <p>Nothing here is formatted for display. There is no colour, no label, no timecode
 * string: a consumer derives those from these facts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClipSnapshot(
        String clipId,
        String assetId,
        String assetName,
        String trackId,
        long start,
        long duration,
        long sourceIn,
        long sourceOut,
        Rational sourceFrameRate) {

    /** The frame after the last one this clip occupies. */
    public long end() {
        return start + duration;
    }

    /**
     * The first source frame, restated so that a consumer reading only the JSON does not have
     * to know which of the two endpoint conventions applies where.
     */
    public long sourceDuration() {
        return sourceOut - sourceIn + 1;
    }
}
