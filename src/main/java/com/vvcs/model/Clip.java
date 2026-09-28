package com.vvcs.model;

/**
 * One placement of an {@link Asset} on a timeline track.
 *
 * <p>A clip is a usage, not a file: the same asset can be placed any number of times and
 * each placement gets its own identity. Its identity is independent of the source project's
 * identifiers so that a clip keeps its meaning after being moved, trimmed or re-sequenced.
 *
 * <h2>The two identifiers on a clip</h2>
 *
 * <p>{@code id} is assigned by whoever built this model and is positional: it numbers clips
 * in the order they are walked. It is short, readable, and stable only as long as nothing
 * earlier on the timeline changes. It identifies a clip <em>within one state</em> and is not
 * what history is matched on.
 *
 * <p>{@code sourceId} is the identifier the editor itself attaches to the thing this entry
 * plays. In Kdenlive that is {@code kdenlive:id}, which is assigned once when the clip is
 * created and follows the clip for as long as the clip exists, through moves, trims and
 * insertions elsewhere on the timeline. It is what makes a clip recognisable across two
 * states.
 *
 * <p>{@code kdenlive:id} is not unique to one occurrence in the strict sense: the audio and
 * video halves of an AV split share one value. Those halves are always on different tracks,
 * so the pair (sourceId, track) is unique, and the diff engine relies on exactly that.
 *
 * <p>{@code sourceId} is nullable, because an editor may not provide one. Nothing else may
 * depend on it being present; see {@code com.vvcs.diff.OccurrenceKey} for the fallback.
 */
public record Clip(
        String id,
        String assetId,
        String trackId,
        SourceRange source,
        TimeRange timeline,
        String sourceId) {

    public Clip {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("clip id must be set");
        }
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("clip " + id + " has no asset reference");
        }
        if (source == null) {
            throw new IllegalArgumentException("clip " + id + " has no source range");
        }
        if (timeline == null) {
            throw new IllegalArgumentException("clip " + id + " has no timeline placement");
        }
    }

    /** A clip whose editor provided no occurrence identifier of its own. */
    public static Clip of(String id, String assetId, String trackId, SourceRange source,
                          TimeRange timeline) {
        return new Clip(id, assetId, trackId, source, timeline, null);
    }
}
