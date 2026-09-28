package com.vvcs.diff;

import com.vvcs.model.Clip;

/**
 * A clip as one side of a comparison, with the facts a diff reports held as facts.
 *
 * <p>Nothing here is formatted, and no field is a rendered sentence. A caller decides how to
 * present a change; this type only carries what changed.
 *
 * @param clipId          the id the clip has in the state it came from, which is positional
 *                        and so differs between the two sides
 * @param key             the stable key that matched this clip to the other side
 * @param assetId         the media the clip plays
 * @param trackId         the track the clip sits on in this state
 * @param timelineStart   first frame of the clip on the timeline
 * @param timelineDuration  length of the clip in frames, a half-open interval
 * @param sourceIn        first frame of the source material used
 * @param sourceOut       last frame of the source material used, inclusive
 * @param orderOnTrack    position among the clips of its track in timeline order
 */
public record ClipOccurrence(
        String clipId,
        String key,
        String assetId,
        String trackId,
        long timelineStart,
        long timelineDuration,
        long sourceIn,
        long sourceOut,
        int orderOnTrack) {

    public static ClipOccurrence of(Clip clip, String key, int orderOnTrack) {
        return new ClipOccurrence(
                clip.id(),
                key,
                clip.assetId(),
                clip.trackId(),
                clip.timeline().start(),
                clip.timeline().duration(),
                clip.source().inFrame(),
                clip.source().outFrame(),
                orderOnTrack);
    }
}
