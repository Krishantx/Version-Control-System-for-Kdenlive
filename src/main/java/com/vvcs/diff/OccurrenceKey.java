package com.vvcs.diff;

import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which clip in one state is the same clip as a clip in another state.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A clip's own {@code id} is positional. It numbers clips in the order the parser walks
 * them, so inserting a clip at the head of a track renumbers every clip after it. Matching
 * states on that id would report a whole track as rewritten when a single clip was added.
 *
 * <h2>The strategy</h2>
 *
 * <p>An occurrence is identified by the editor's own identifier for the entry, when there is
 * one. The key deliberately does <em>not</em> name the track, so a clip that is dragged onto
 * another track is still recognised as the same clip and can be reported as having changed
 * track, rather than as a removal and an addition.
 *
 * <p>There is one case where an identifier is not unique. The audio and video halves of an AV
 * split share a single {@code kdenlive:id}. Those two occurrences are told apart by their
 * tracks, which is safe precisely because an AV split puts them on different tracks.
 *
 * <p>When the editor provides no identifier at all, the fallback is the asset the clip plays,
 * its track, and its ordinal among the clips of that track playing the same asset.
 *
 * <p>Nothing positional takes part: no timeline start, no duration, no array index. A clip
 * that is moved, trimmed, or has a neighbour inserted before it keeps its key, which is
 * exactly the property the diff engine needs in order to tell movement from a rewrite.
 *
 * <p>Tracks are keyed by type and by their ordinal among tracks of that type, so adding a
 * new audio track does not renumber the video tracks. That is weaker than a real track
 * identity, and {@link TrackKey} says so where the limitation belongs.
 */
public final class OccurrenceKey {

    private final Map<String, String> keyByClipId = new LinkedHashMap<>();
    private final Map<String, String> trackKeyById = new LinkedHashMap<>();

    private OccurrenceKey() {
    }

    /**
     * Keys every clip and every track of {@code project}.
     */
    public static OccurrenceKey of(Project project) {
        OccurrenceKey keys = new OccurrenceKey();

        // How many occurrences share each editor identifier. An identifier used once is
        // unambiguous on its own; one used more than once is an AV split and needs the track.
        Map<String, Integer> occurrencesPerSourceId = new LinkedHashMap<>();
        for (Clip clip : clipsInOrder(project)) {
            if (hasIdentifier(clip)) {
                occurrencesPerSourceId.merge(clip.sourceId(), 1, Integer::sum);
            }
        }

        int ordinalPerType = 0;
        TrackType previousType = null;
        for (Track track : project.timeline().tracks()) {
            // Tracks of the same type are numbered among themselves, so inserting a track of
            // a different type leaves the existing keys untouched.
            if (track.type() != previousType) {
                ordinalPerType = 0;
                previousType = track.type();
            }
            ordinalPerType++;
            String trackKey = track.type().name().toLowerCase(Locale.ROOT) + "_" + ordinalPerType;
            keys.trackKeyById.put(track.id(), trackKey);
            keys.keyTrack(track, trackKey, occurrencesPerSourceId);
        }
        return keys;
    }

    private void keyTrack(Track track, String trackKey, Map<String, Integer> occurrencesPerSourceId) {
        Map<String, Integer> seenPerAsset = new LinkedHashMap<>();
        for (Clip clip : track.clips()) {
            int ordinal = seenPerAsset.merge(clip.assetId(), 1, Integer::sum) - 1;
            String candidate = key(trackKey, clip, ordinal, occurrencesPerSourceId);
            // A key that is already taken would silently drop a clip from the comparison, so
            // widen it until it is not. This should not arise in practice.
            String unique = candidate;
            for (int suffix = 2; keyByClipId.containsValue(unique); suffix++) {
                unique = candidate + "~" + suffix;
            }
            keyByClipId.put(clip.id(), unique);
        }
    }

    private static String key(String trackKey, Clip clip, int ordinal,
                              Map<String, Integer> occurrencesPerSourceId) {
        if (hasIdentifier(clip)) {
            boolean shared = occurrencesPerSourceId.getOrDefault(clip.sourceId(), 1) > 1;
            // The editor's identifier, which follows the clip through moves and trims. The
            // track is only named when the identifier alone cannot tell occurrences apart.
            return shared ? "e:" + clip.sourceId() + "@" + trackKey : "e:" + clip.sourceId();
        }
        return "c:" + clip.assetId() + "@" + trackKey + "#" + ordinal;
    }

    private static boolean hasIdentifier(Clip clip) {
        return clip.sourceId() != null && !clip.sourceId().isBlank();
    }

    /**
     * The key of a clip, or {@code null} if the clip was not part of the project these keys
     * were built from.
     */
    public String clipKey(Clip clip) {
        return keyByClipId.get(clip.id());
    }

    /**
     * The key of a track, or {@code null} for a track that was not present.
     */
    public String trackKey(Track track) {
        return trackKeyById.get(track.id());
    }

    /** Every clip of a project, in a stable order, for diffing. */
    public static List<Clip> clipsInOrder(Project project) {
        List<Clip> clips = new ArrayList<>();
        for (Track track : project.timeline().tracks()) {
            clips.addAll(track.clips());
        }
        return clips;
    }

    /**
     * The position of a clip among the clips of its track, counted in timeline order.
     * Positions are compared between states to recognise a reorder.
     */
    public static int orderOnTrack(Project project, Clip clip) {
        for (Track track : project.timeline().tracks()) {
            if (track.id().equals(clip.trackId())) {
                List<Clip> ordered = new ArrayList<>(track.clips());
                ordered.sort(Comparator.comparingLong(c -> c.timeline().start()));
                for (int i = 0; i < ordered.size(); i++) {
                    if (ordered.get(i).id().equals(clip.id())) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }
}
