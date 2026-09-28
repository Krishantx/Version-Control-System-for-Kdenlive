package com.vvcs.diff;

import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cases a version control system for editing has to get right: recognising a clip that
 * moved, was trimmed, or crossed tracks, rather than reporting it as a rewrite.
 */
class DiffEngineTest {

    private final DiffEngine engine = new DiffEngine();

    /**
     * Asserts that exactly one change came back, so a test that expects a single change
     * cannot silently pass on a list that also contains a second one.
     */
    private static <T> T only(List<T> changes) {
        assertEquals(1, changes.size(), "expected exactly one change but got " + changes);
        return changes.get(0);
    }

    @Test
    @DisplayName("an unchanged project has no changes at all")
    void anUnchangedProjectIsEmpty() {
        Project project = DiffFixtures.twoClipsInOrder();
        TimelineDiff diff = engine.diff(project, project);

        assertTrue(diff.isEmpty());
        assertEquals(List.of(), diff.clipChanges());
    }

    @Test
    @DisplayName("a clip shifted along the timeline is MOVED, not removed and added")
    void aShiftedClipIsMoved() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 25, 50));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertEquals(ChangeType.MOVED, change.type());
        assertEquals(0, change.before().timelineStart());
        assertEquals(25, change.after().timelineStart());
    }

    @Test
    @DisplayName("a clip shortened at its tail is TRIMMED, keeping its start")
    void aShortenedClipIsTrimmed() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 30));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertEquals(ChangeType.TRIMMED, change.type());
        assertEquals(0, change.before().timelineStart());
        assertEquals(0, change.after().timelineStart());
        assertEquals(50, change.before().timelineDuration());
        assertEquals(30, change.after().timelineDuration());
    }

    @Test
    @DisplayName("a clip that both moved and was trimmed keeps both facts")
    void aMovedAndTrimmedClipIsOneChangeWithTwoFacts() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 10, 30));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertTrue(change.kinds().contains(ChangeType.MOVED));
        assertTrue(change.kinds().contains(ChangeType.TRIMMED));
        assertEquals(2, change.kinds().size());
        assertEquals(ChangeType.MOVED, change.type(), "the dominant type is the more telling one");
    }

    @Test
    @DisplayName("a clip taken from one track and put on another is TRACK_CHANGED, still one clip")
    void aClipMovedToAnotherTrackKeepsItsIdentity() {
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)),
                DiffFixtures.audio("track_002"));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001"),
                DiffFixtures.audio("track_002",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_002", 0, 50)));

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        ClipChange change = only(changes);
        assertEquals(ChangeType.TRACK_CHANGED, change.type());
        assertEquals("track_001", change.before().trackId());
        assertEquals("track_002", change.after().trackId());
    }

    @Test
    @DisplayName("a clip that changes track is not confused with a reorder on that track")
    void acrossTrackMovementIsNotAReorder() {
        // Moving a clip to a different track changes its ordinal on the timeline, but that is
        // a change of track, not a reordering: the ordinals being compared belong to two
        // different tracks and mean nothing to each other.
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50),
                        DiffFixtures.clip("clip_002", "e2", "asset_1", "track_001", 50, 50)),
                DiffFixtures.video("track_002"));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_002", "e2", "asset_1", "track_001", 50, 50)),
                DiffFixtures.video("track_002",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_002", 0, 50)));

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        ClipChange change = only(changes.stream()
                .filter(c -> c.before().clipId().equals("clip_001")).toList());
        assertEquals(ChangeType.TRACK_CHANGED, change.type());
        assertFalse(change.kinds().contains(ChangeType.REORDERED));

        // The other clip really did change: it is now first on track_001 rather than second,
        // which is a genuine reorder even though it stayed on the same track.
        ClipChange reordered = only(changes.stream()
                .filter(c -> c.before().clipId().equals("clip_002")).toList());
        assertTrue(reordered.kinds().contains(ChangeType.REORDERED));
        assertFalse(reordered.kinds().contains(ChangeType.TRACK_CHANGED));
    }

    @Test
    @DisplayName("swapping two clips on a track is REORDERED for both")
    void swappedClipsAreReordered() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.twoClipsReordered();

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        assertEquals(2, changes.size());
        for (ClipChange change : changes) {
            assertTrue(change.kinds().contains(ChangeType.REORDERED),
                    "clip " + change.clipId() + " should be reported as reordered");
        }
    }

    @Test
    @DisplayName("a clip is still the same clip when a clip is inserted before it")
    void insertingAClipDoesNotRenumberTheOthers() {
        // The occurrence ids in the XML are e1 and e2; after the insertion the parser walks
        // the new clip first, so the positional ids e1 and e2 swap. Identity must come from
        // the editor's identifier, or the whole track would look rewritten.
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.placedAt("clip_001", "e1", "asset_1", "track_001", 0, 50, 0),
                        DiffFixtures.placedAt("clip_002", "e2", "asset_1", "track_001", 50, 50, 0)));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e3", "asset_2", "track_001", 0, 20),
                        DiffFixtures.placedAt("clip_002", "e1", "asset_1", "track_001", 20, 50, 0),
                        DiffFixtures.placedAt("clip_003", "e2", "asset_1", "track_001", 70, 50, 0)));

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        ClipChange added = only(changes.stream()
                .filter(c -> c.kinds().contains(ChangeType.ADDED)).toList());
        assertEquals("e:e3", added.after().key());

        for (ClipChange change : changes) {
            if (!change.kinds().contains(ChangeType.ADDED)) {
                // Inserting a clip ahead of them pushes them along the timeline and back one
                // place, so MOVED and REORDERED are both right. What must not happen is a
                // removal and an addition: the key is what says these are the same clips.
                assertTrue(change.kinds().contains(ChangeType.MOVED),
                        "an existing clip should be reported as moved, not rewritten");
                assertFalse(change.kinds().contains(ChangeType.REMOVED));
                assertTrue(Set.of("e:e1", "e:e2").contains(change.key()),
                        "the key must come from the editor's identifier, not the position");
            }
        }
    }

    @Test
    @DisplayName("an added clip is ADDED, carrying the new state only")
    void anAddedClipIsReportedWithOneSide() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50),
                        DiffFixtures.clip("clip_003", "e3", "asset_2", "track_001", 100, 50),
                        DiffFixtures.clip("clip_002", "e2", "asset_1", "track_001", 50, 50)));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertEquals(ChangeType.ADDED, change.type());
        assertNull(change.before());
        assertNotNull(change.after());
    }

    @Test
    @DisplayName("a removed clip is REMOVED, carrying the old state only")
    void aRemovedClipIsReportedWithOneSide() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertEquals(ChangeType.REMOVED, change.type());
        assertNotNull(change.before());
        assertNull(change.after());
    }

    @Test
    @DisplayName("a clip that switches to different media is not a move of the same clip")
    void changingTheMediaIsNotAMove() {
        Project before = DiffFixtures.twoClipsInOrder();
        // Same editor identifier, but now pointing at other media. The occurrence is the
        // same placement, so it is a modification rather than a removal and an addition.
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_001", "e1", "asset_2", "track_001", 0, 50));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertEquals(ChangeType.MODIFIED, change.type());
        assertEquals("asset_1", change.before().assetId());
        assertEquals("asset_2", change.after().assetId());
    }

    @Test
    @DisplayName("a clip whose source moved but whose placement is the same is TRIMMED")
    void aSlippedSourceIsATrim() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50, 10, 59));

        ClipChange change = only(engine.diff(before, after).clipChanges());

        assertEquals(ChangeType.TRIMMED, change.type());
        assertEquals(0, change.before().sourceIn());
        assertEquals(10, change.after().sourceIn());
    }

    @Test
    @DisplayName("an added track is ADDED and the clips on other tracks are untouched")
    void anAddedTrackDoesNotDisturbTheOthers() {
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)),
                DiffFixtures.audio("track_002"));

        TimelineDiff diff = engine.diff(before, after);

        assertEquals(1, diff.trackChanges().size());
        assertEquals(ChangeType.ADDED, diff.trackChanges().get(0).type());
        assertEquals(TrackType.AUDIO, diff.trackChanges().get(0).afterType());
        assertTrue(diff.clipChanges().isEmpty(), "no clip on the existing track changed");
    }

    @Test
    @DisplayName("a removed track is REMOVED and its clips go with it")
    void aRemovedTrackRemovesItsClips() {
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)),
                DiffFixtures.audio("track_002",
                        DiffFixtures.clip("clip_002", "e2", "asset_2", "track_002", 0, 50)));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));

        TimelineDiff diff = engine.diff(before, after);

        assertEquals(1, diff.trackChanges().size());
        assertEquals(ChangeType.REMOVED, diff.trackChanges().get(0).type());
        assertEquals(ChangeType.REMOVED, only(diff.clipChanges()).type());
    }

    @Test
    @DisplayName("an added asset is ADDED and a changed asset is MODIFIED")
    void assetChangesAreReported() {
        Project before = DiffFixtures.project(List.of(DiffFixtures.asset("asset_1")),
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
        Project after = DiffFixtures.project(
                List.of(new Asset("asset_1", "asset_1.mp4", "/media/asset_1.mp4", "rehashed",
                        new com.vvcs.model.TimeSpan(1000, DiffFixtures.FPS), DiffFixtures.HD,
                        "h264", "aac"),
                        DiffFixtures.asset("asset_2")),
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));

        List<AssetChange> changes = engine.diff(before, after).assetChanges();

        assertEquals(2, changes.size());
        assertEquals("asset_1", changes.get(0).assetId());
        assertEquals(ChangeType.MODIFIED, changes.get(0).type());
        assertEquals("hash-asset_1", changes.get(0).before().hash());
        assertEquals("rehashed", changes.get(0).after().hash());
        assertEquals("asset_2", changes.get(1).assetId());
        assertEquals(ChangeType.ADDED, changes.get(1).type());
    }

    @Test
    @DisplayName("the timeline length is reported from both states")
    void theTimelineLengthIsReported() {
        Project before = DiffFixtures.twoClipsInOrder();
        // The last clip on the track is the one that sets the end of the timeline.
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_002", "e2", "asset_1", "track_001", 50, 80));

        TimelineDiff diff = engine.diff(before, after);

        assertEquals(100, diff.fromDuration());
        assertEquals(130, diff.toDuration());
        assertFalse(diff.isEmpty());
    }

    @Test
    @DisplayName("state identities are carried through when the caller supplies them")
    void stateIdentitiesAreCarriedThrough() {
        Project project = DiffFixtures.twoClipsInOrder();

        TimelineDiff diff = engine.diff("state-a", "state-b", project, project);

        assertEquals("state-a", diff.fromStateId());
        assertEquals("state-b", diff.toStateId());
    }

    @Test
    @DisplayName("a clip with no editor identifier is matched on its media and its place in line")
    void aClipWithoutAnEditorIdentifierStillHasAnIdentity() {
        // The same two placements, with the first one shortened. With no editor identifier
        // the media and the ordinal are all that is left to match on, and that is enough:
        // the shortened clip is a trim, and the one it pushed along is a move.
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", null, "asset_1", "track_001", 0, 50, 0, 49),
                        DiffFixtures.clip("clip_002", "e2", "asset_2", "track_001", 50, 50, 200, 249)));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", null, "asset_1", "track_001", 0, 30, 0, 29),
                        DiffFixtures.clip("clip_002", "e2", "asset_2", "track_001", 30, 50, 200, 249)));

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        assertEquals(2, changes.size());
        ClipChange trimmed = only(changes.stream()
                .filter(c -> c.before().clipId().equals("clip_001")).toList());
        assertEquals(ChangeType.TRIMMED, trimmed.type());
        assertEquals("c:asset_1@video_1#0", trimmed.key(), "matched on media, track and ordinal");

        ClipChange moved = only(changes.stream()
                .filter(c -> c.before().clipId().equals("clip_002")).toList());
        assertEquals(ChangeType.MOVED, moved.type());
    }

    @Test
    @DisplayName("diffing the same project twice gives the same answer")
    void theResultIsDeterministic() {
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.twoClipsReordered();

        assertEquals(engine.diff(before, after).clipChanges(),
                engine.diff(before, after).clipChanges());
    }

    @Test
    @DisplayName("the audio and video halves of an AV split stay distinguishable")
    void avSplitHalvesAreDistinctOccurrences() {
        // A real Kdenlive AV split gives both halves one kdenlive:id. They are on different
        // tracks, so the pair (identifier, track) still tells them apart.
        Project before = DiffFixtures.project(
                DiffFixtures.audio("track_001",
                        DiffFixtures.clip("clip_001", "7", "asset_1", "track_001", 0, 50)),
                DiffFixtures.video("track_002",
                        DiffFixtures.clip("clip_002", "7", "asset_1", "track_002", 0, 50)));
        Project after = DiffFixtures.project(
                DiffFixtures.audio("track_001",
                        DiffFixtures.clip("clip_001", "7", "asset_1", "track_001", 0, 30)),
                DiffFixtures.video("track_002",
                        DiffFixtures.clip("clip_002", "7", "asset_1", "track_002", 0, 50)));

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        ClipChange change = only(changes);
        assertEquals("clip_001", change.clipId(),
                "only the audio half was trimmed; the video half is a separate occurrence");
        assertEquals(ChangeType.TRIMMED, change.type());
    }

    @Test
    @DisplayName("adding a track of a different type leaves the other tracks' keys alone")
    void addingATrackOfAnotherTypeKeepsTheKeys() {
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
        Project after = DiffFixtures.project(
                DiffFixtures.audio("track_009"),
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));

        TimelineDiff diff = engine.diff(before, after);

        assertTrue(diff.clipChanges().isEmpty(),
                "the video clip is the same occurrence in both states");
        assertEquals(1, diff.trackChanges().size());
        assertEquals(ChangeType.ADDED, diff.trackChanges().get(0).type());
    }

    @Test
    @DisplayName("the engine holds no state between calls")
    void theEngineIsReusable() {
        DiffEngine shared = new DiffEngine();
        Project before = DiffFixtures.twoClipsInOrder();
        Project after = DiffFixtures.replacingClip(before,
                DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 30));

        TimelineDiff first = shared.diff(before, after);
        TimelineDiff second = shared.diff(before, after);
        TimelineDiff reverse = shared.diff(after, before);

        assertEquals(first.clipChanges(), second.clipChanges());
        ClipChange backwards = only(reverse.clipChanges());
        assertEquals(ChangeType.TRIMMED, backwards.type(),
                "reversing the comparison keeps the change and swaps its sides");
        assertEquals(first.clipChanges().get(0).before(), backwards.after());
        assertEquals(first.clipChanges().get(0).after(), backwards.before());
    }

    @Test
    @DisplayName("a clip the parser renumbered is not reported as changed")
    void aRenumberedClipIsNotAChange() {
        // Moving a clip to an earlier track renumbers every clip, because the parser numbers
        // them as it walks. The clip that did not move must come back with no change at all,
        // even though its id is different on the two sides.
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50),
                        DiffFixtures.clip("clip_002", "e2", "asset_1", "track_001", 50, 50)),
                DiffFixtures.video("track_002"));
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_002", "e2", "asset_1", "track_001", 0, 50)),
                DiffFixtures.video("track_002",
                        DiffFixtures.clip("clip_003", "e1", "asset_1", "track_002", 0, 50)));

        List<ClipChange> changes = engine.diff(before, after).clipChanges();

        ClipChange change = only(changes.stream()
                .filter(c -> c.key().equals("e:e1")).toList());
        assertEquals(ChangeType.TRACK_CHANGED, change.type());
        assertFalse(change.kinds().contains(ChangeType.MODIFIED),
                "a renumbered clip that did not otherwise change is not a modification");

        // e2 kept every fact about itself and only moved, so exactly one change per clip.
        assertEquals(2, changes.size());
    }

    @Test
    @DisplayName("a clip whose only difference is its id is not reported at all")
    void anIdenticalClipUnderAnotherIdIsNoChange() {
        Project before = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
        // The very same placement, with the parser having numbered it differently.
        Project after = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_009", "e1", "asset_1", "track_001", 0, 50)));

        assertTrue(engine.diff(before, after).isEmpty(),
                "the same occurrence under a different positional id is not a change");
    }

    @Test
    @DisplayName("the dominant type prefers the more telling of two changes")
    void theDominantTypeIsTheMostInformative() {
        assertEquals(ChangeType.ADDED, ChangeType.dominant(ChangeType.ADDED, ChangeType.TRIMMED));
        assertEquals(ChangeType.TRACK_CHANGED,
                ChangeType.dominant(ChangeType.REORDERED, ChangeType.TRACK_CHANGED));
        assertEquals(ChangeType.MOVED, ChangeType.dominant(ChangeType.MOVED, ChangeType.TRIMMED),
                "a clip that moved is reported as a move, with the trim carried alongside");
        assertEquals(ChangeType.MODIFIED, ChangeType.dominant(ChangeType.MODIFIED));
    }

    @Test
    @DisplayName("a project with no clips diffs cleanly")
    void anEmptyTimelineIsHandled() {
        Project before = DiffFixtures.project(DiffFixtures.video("track_001"));
        Project after = DiffFixtures.project(DiffFixtures.video("track_001"));

        assertTrue(engine.diff(before, after).isEmpty());
    }

    @Test
    @DisplayName("clip keys come from the editor's identifier, not the positional id")
    void keysPreferTheEditorIdentifier() {
        Project project = DiffFixtures.project(
                DiffFixtures.video("track_001",
                        DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));

        OccurrenceKey keys = OccurrenceKey.of(project);
        Clip clip = project.timeline().tracks().get(0).clips().get(0);

        assertEquals("e:e1", keys.clipKey(clip));
        assertSame(clip, project.timeline().tracks().get(0).clips().get(0));
    }

    @Test
    @DisplayName("tracks are keyed by type and ordinal among their own type")
    void tracksAreKeyedByTypeAndOrdinal() {
        Project project = DiffFixtures.project(
                DiffFixtures.audio("track_001"),
                DiffFixtures.audio("track_002"),
                DiffFixtures.video("track_003"));

        OccurrenceKey keys = OccurrenceKey.of(project);
        List<Track> tracks = project.timeline().tracks();

        assertEquals("audio_1", keys.trackKey(tracks.get(0)));
        assertEquals("audio_2", keys.trackKey(tracks.get(1)));
        assertEquals("video_1", keys.trackKey(tracks.get(2)));
    }
}
