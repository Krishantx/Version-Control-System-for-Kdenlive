package com.vvcs.diff;

import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two project states and reports what changed, structurally.
 *
 * <p>The engine is the reusable core: it knows about the VVCS model and nothing else. It does
 * not read files, does not know a repository exists, does not know which editor the state
 * came from, and never renders text. Any caller can hand it two {@link Project} values and
 * receive a {@link TimelineDiff}.
 *
 * <h2>How clips are matched</h2>
 *
 * <p>Clips are matched through {@link OccurrenceKey}, which never consults a clip's
 * positional id. That is what allows a clip to be recognised as moved, trimmed, or moved to
 * another track instead of being reported as a removal and an addition.
 */
public final class DiffEngine {

    /**
     * Compares two states.
     *
     * @param older the state being compared from
     * @param newer the state being compared to
     */
    public TimelineDiff diff(Project older, Project newer) {
        return diff(null, null, older, newer);
    }

    /**
     * Compares two states, labelling the result with the states' identities when the caller
     * has them. The identities are passed in rather than looked up so that this stays free of
     * any repository dependency.
     */
    public TimelineDiff diff(String fromStateId, String toStateId, Project older, Project newer) {
        return new TimelineDiff(fromStateId, toStateId,
                assetChanges(older, newer),
                trackChanges(older, newer),
                clipChanges(older, newer),
                older.timeline().duration(),
                newer.timeline().duration());
    }

    private List<AssetChange> assetChanges(Project older, Project newer) {
        Map<String, Asset> before = byId(older.assets());
        Map<String, Asset> after = byId(newer.assets());
        List<AssetChange> changes = new ArrayList<>();
        for (Map.Entry<String, Asset> entry : after.entrySet()) {
            Asset was = before.get(entry.getKey());
            if (was == null) {
                changes.add(AssetChange.added(entry.getValue()));
            } else if (!was.equals(entry.getValue())) {
                changes.add(AssetChange.modified(was, entry.getValue()));
            }
        }
        for (Map.Entry<String, Asset> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                changes.add(AssetChange.removed(entry.getValue()));
            }
        }
        changes.sort(Comparator.comparing(AssetChange::assetId));
        return changes;
    }

    private List<TrackChange> trackChanges(Project older, Project newer) {
        Map<String, Track> before = byId(tracks(older));
        Map<String, Track> after = byId(tracks(newer));
        List<TrackChange> changes = new ArrayList<>();
        for (Map.Entry<String, Track> entry : after.entrySet()) {
            Track was = before.get(entry.getKey());
            if (was == null) {
                changes.add(new TrackChange(trackKey(newer, entry.getValue()), ChangeType.ADDED,
                        null, entry.getValue().type(), -1, indexOfType(newer, entry.getValue())));
            } else if (was.type() != entry.getValue().type()) {
                changes.add(new TrackChange(trackKey(newer, entry.getValue()), ChangeType.MODIFIED,
                        was.type(), entry.getValue().type(),
                        indexOfType(older, was), indexOfType(newer, entry.getValue())));
            }
        }
        for (Map.Entry<String, Track> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                changes.add(new TrackChange(trackKey(older, entry.getValue()), ChangeType.REMOVED,
                        entry.getValue().type(), null,
                        indexOfType(older, entry.getValue()), -1));
            }
        }
        changes.sort(Comparator.comparing(TrackChange::key));
        return changes;
    }

    private List<ClipChange> clipChanges(Project older, Project newer) {
        OccurrenceKey oldKeys = OccurrenceKey.of(older);
        OccurrenceKey newKeys = OccurrenceKey.of(newer);
        Map<String, ClipOccurrence> before = occurrences(older, oldKeys);
        Map<String, ClipOccurrence> after = occurrences(newer, newKeys);

        List<ClipChange> changes = new ArrayList<>();
        for (Map.Entry<String, ClipOccurrence> entry : after.entrySet()) {
            ClipOccurrence was = before.get(entry.getKey());
            if (was == null) {
                changes.add(ClipChange.added(entry.getValue()));
            } else {
                ClipChange change = compared(was, entry.getValue());
                if (change != null) {
                    changes.add(change);
                }
            }
        }
        for (Map.Entry<String, ClipOccurrence> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                changes.add(ClipChange.removed(entry.getValue()));
            }
        }
        changes.sort(Comparator
                .comparing((ClipChange c) -> c.after() != null ? c.after().clipId() : c.before().clipId())
                .thenComparingLong(c -> c.after() != null
                        ? c.after().timelineStart() : c.before().timelineStart()));
        return changes;
    }

    /**
     * Classifies one matched pair, or returns null when the clip is untouched. Each aspect is
     * decided on its own so that a clip which moved and was trimmed is reported as one change
     * carrying both facts.
     */
    private ClipChange compared(ClipOccurrence before, ClipOccurrence after) {
        if (!changed(before, after)) {
            return null;
        }
        java.util.Set<ChangeType> kinds = new java.util.LinkedHashSet<>();

        if (!before.trackId().equals(after.trackId())) {
            kinds.add(ChangeType.TRACK_CHANGED);
        }
        boolean reordered = before.orderOnTrack() != after.orderOnTrack()
                && before.trackId().equals(after.trackId());
        if (reordered) {
            kinds.add(ChangeType.REORDERED);
        }
        boolean moved = before.timelineStart() != after.timelineStart();
        if (moved) {
            kinds.add(ChangeType.MOVED);
        }
        boolean trimmed = before.timelineDuration() != after.timelineDuration()
                || before.sourceIn() != after.sourceIn()
                || before.sourceOut() != after.sourceOut();
        if (trimmed) {
            kinds.add(ChangeType.TRIMMED);
        }

        if (kinds.isEmpty()) {
            // Something about the clip changed that is not position, length, or source: the
            // media it plays. That is more than a bare modification but has no better name.
            kinds.add(ChangeType.MODIFIED);
        }
        ChangeType dominant = ChangeType.dominant(kinds.toArray(new ChangeType[0]));
        return new ClipChange(after.key(), dominant, kinds, before, after);
    }

    /**
     * Whether anything an editor would call a change actually changed.
     *
     * <p>Deliberately does not consult the clip's id. A clip's id is assigned by the order it
     * was walked in, so it can differ between two states while the clip itself is untouched;
     * comparing it would report an edit that nobody made.
     */
    private static boolean changed(ClipOccurrence before, ClipOccurrence after) {
        return !before.assetId().equals(after.assetId())
                || !before.trackId().equals(after.trackId())
                || before.timelineStart() != after.timelineStart()
                || before.timelineDuration() != after.timelineDuration()
                || before.sourceIn() != after.sourceIn()
                || before.sourceOut() != after.sourceOut()
                || before.orderOnTrack() != after.orderOnTrack();
    }

    private Map<String, ClipOccurrence> occurrences(Project project, OccurrenceKey keys) {
        Map<String, ClipOccurrence> occurrences = new LinkedHashMap<>();
        for (Clip clip : OccurrenceKey.clipsInOrder(project)) {
            String key = keys.clipKey(clip);
            occurrences.put(key, ClipOccurrence.of(clip, key,
                    OccurrenceKey.orderOnTrack(project, clip)));
        }
        return occurrences;
    }

    private static int indexOfType(Project project, Track track) {
        int index = 0;
        for (Track candidate : project.timeline().tracks()) {
            if (candidate.type() == track.type()) {
                if (candidate.id().equals(track.id())) {
                    return index;
                }
                index++;
            }
        }
        return -1;
    }

    private static String trackKey(Project project, Track track) {
        return OccurrenceKey.of(project).trackKey(track);
    }

    private static List<Track> tracks(Project project) {
        return project.timeline().tracks();
    }

    private static <T> Map<String, T> byId(List<T> items) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            String id = idOf(item);
            if (id != null) {
                map.put(id, item);
            }
        }
        return map;
    }

    private static String idOf(Object item) {
        if (item instanceof Asset asset) {
            return asset.id();
        }
        if (item instanceof Track track) {
            return track.id();
        }
        return null;
    }
}
