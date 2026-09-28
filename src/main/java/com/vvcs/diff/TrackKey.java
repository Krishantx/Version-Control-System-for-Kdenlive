package com.vvcs.diff;

/**
 * Where a track's identity comes from, and what that means for track reordering.
 *
 * <h2>The limitation</h2>
 *
 * <p>The current semantic model has no field that means "this is the same track as the one
 * in the other state". A track is an id, a type, and a list of clips, and the parser assigns
 * ids by walking order. There is therefore no editor-provided track identity to carry, and
 * none is invented here.
 *
 * <p>Tracks are keyed by their type and by their ordinal among the tracks of that type:
 * {@code video_1}, {@code audio_2}. The choice buys two things and gives up one.
 *
 * <p>Bought: adding a track of a different type, or appending one, leaves existing keys
 * alone. Every clip on every other track keeps its key, so the diff reports the one addition
 * rather than a rewritten timeline.
 *
 * <p>Given up: reordering two video tracks is not detectable, because after the reorder
 * {@code video_1} and {@code video_2} still carry the same keys and merely exchange their
 * contents, so the change surfaces as the clips of those two tracks having moved between
 * them. That is reported as {@link ChangeType#TRACK_CHANGED} on the clips rather than as a
 * reordered track, which is honest but less direct than it would be with a real track
 * identity.
 *
 * <p>Lifting this needs a field in {@link com.vvcs.model.Track} carrying the editor's own
 * identifier for the track, which is a model change this phase deliberately does not make.
 */
public final class TrackKey {

    private TrackKey() {
    }
}
