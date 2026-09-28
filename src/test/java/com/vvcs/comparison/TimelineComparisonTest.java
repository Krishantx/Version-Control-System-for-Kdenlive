package com.vvcs.comparison;

import com.vvcs.diff.ChangeType;
import com.vvcs.diff.ClipChange;
import com.vvcs.diff.DiffFixtures;
import com.vvcs.diff.TimelineDiff;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.vvcs.diff.DiffFixtures.asset;
import static com.vvcs.diff.DiffFixtures.clip;
import static com.vvcs.diff.DiffFixtures.placedAt;
import static com.vvcs.diff.DiffFixtures.project;
import static com.vvcs.diff.DiffFixtures.video;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a consumer receives: two whole timelines and what differs between them.
 *
 * <p>These tests are written from the consumer's side. Each one states the change being made,
 * and then asks the questions a frontend would have to answer to draw it, so that a gap in the
 * model shows up here as a missing fact rather than as a question the consumer turns out to be
 * unable to answer.
 */
class TimelineComparisonTest {

    private static final TimelineComparisonService SERVICE = new TimelineComparisonService(null);

    private static ProjectState state(String id, Project project) {
        return new ProjectState(id, project);
    }

    private static ClipChange only(ClipChange... changes) {
        assertEquals(1, changes.length, "expected exactly one clip change");
        return changes[0];
    }

    @Nested
    @DisplayName("both sides are always present")
    class BothSides {

        @Test
        @DisplayName("a comparison of a state with itself still carries both timelines")
        void identicalStatesCarryBothTimelines() {
            Project project = DiffFixtures.twoClipsInOrder();

            TimelineComparison comparison =
                    SERVICE.compare(state("state_a", project), state("state_a", project));

            assertAll(
                    () -> assertNotNull(comparison.before()),
                    () -> assertNotNull(comparison.after()),
                    () -> assertEquals("state_a", comparison.before().stateId()),
                    () -> assertEquals("state_a", comparison.after().stateId()),
                    () -> assertTrue(comparison.isEmpty()),
                    () -> assertEquals(0, comparison.changes().clipChanges().size()),
                    () -> assertEquals(2, comparison.before().clipCount()),
                    () -> assertEquals(2, comparison.after().clipCount()),
                    () -> assertEquals(1, comparison.before().trackCount()));
        }

        @Test
        @DisplayName("a snapshot is enough to draw without the changes")
        void snapshotIsSelfSufficient() {
            Project project = DiffFixtures.twoClipsInOrder();

            TimelineSnapshot snapshot = SERVICE.snapshot(state("state_a", project));

            TrackSnapshot track = snapshot.tracks().get(0);
            ClipSnapshot first = track.clips().get(0);
            assertAll(
                    () -> assertEquals("demo", snapshot.projectName()),
                    () -> assertEquals("Main", snapshot.name()),
                    () -> assertEquals(100L, snapshot.duration()),
                    () -> assertEquals(DiffFixtures.FPS, snapshot.frameRate()),
                    () -> assertEquals(DiffFixtures.HD, snapshot.resolution()),
                    () -> assertEquals(0, track.index()),
                    () -> assertEquals("track_001", first.trackId()),
                    () -> assertEquals(0L, first.start()),
                    () -> assertEquals(50L, first.duration()),
                    () -> assertEquals(50L, first.end()),
                    () -> assertEquals("asset_1", first.assetId()));
        }

        @Test
        @DisplayName("a snapshot names the media each clip plays")
        void snapshotResolvesAssetNames() {
            Project project = project(
                    video("track_001", clip("clip_001", "e1", "asset_2", "track_001", 0, 50)));

            ClipSnapshot snapshot = SERVICE.snapshot(state("s", project)).tracks().get(0).clips().get(0);

            assertAll(
                    () -> assertEquals("asset_2", snapshot.assetId()),
                    () -> assertEquals("asset_2.mp4", snapshot.assetName()));
        }

        @Test
        @DisplayName("clips are listed in the order an editor sees them")
        void clipsAreOrderedByPosition() {
            // Given a track whose clips are listed out of order, as a hand-built project can be
            Project unsorted = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 50, 50),
                    clip("clip_002", "e2", "asset_1", "track_001", 0, 50)));

            List<ClipSnapshot> clips =
                    SERVICE.snapshot(state("s", unsorted)).tracks().get(0).clips();

            assertAll(
                    () -> assertEquals("clip_002", clips.get(0).clipId()),
                    () -> assertEquals(0L, clips.get(0).start()),
                    () -> assertEquals("clip_001", clips.get(1).clipId()),
                    () -> assertEquals(50L, clips.get(1).start()));
        }
    }

    @Nested
    @DisplayName("every kind of change is reconstructable from the response")
    class Changes {

        @Test
        @DisplayName("added: present in after, absent from before")
        void added() {
            Project before = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
            Project after = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50),
                    clip("clip_002", "e2", "asset_1", "track_001", 50, 50)));

            ClipChange change = only(SERVICE.compare(state("a", before), state("b", after))
                    .changes().clipChanges().toArray(new ClipChange[0]));

            assertAll(
                    () -> assertEquals(ChangeType.ADDED, change.type()),
                    () -> assertNull(change.before()),
                    () -> assertNotNull(change.after()),
                    () -> assertNull(change.oldStart()),
                    () -> assertEquals(50L, change.newStart()),
                    () -> assertNull(change.oldDuration()),
                    () -> assertEquals(50L, change.newDuration()),
                    () -> assertNull(change.oldAssetId()),
                    () -> assertEquals("asset_1", change.newAssetId()),
                    // The clip is drawable from the response alone
                    () -> assertEquals(2, SERVICE.compare(state("a", before), state("b", after))
                            .after().tracks().get(0).clipCount()));
        }

        @Test
        @DisplayName("removed: present in before, absent from after")
        void removed() {
            Project before = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50),
                    clip("clip_002", "e2", "asset_1", "track_001", 50, 50)));
            Project after = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));

            ClipChange change = only(SERVICE.compare(state("a", before), state("b", after))
                    .changes().clipChanges().toArray(new ClipChange[0]));

            assertAll(
                    () -> assertEquals(ChangeType.REMOVED, change.type()),
                    () -> assertNotNull(change.before()),
                    () -> assertNull(change.after()),
                    () -> assertEquals(50L, change.oldStart()),
                    () -> assertNull(change.newStart()),
                    () -> assertEquals(50L, change.oldDuration()),
                    () -> assertNull(change.newDuration()),
                    () -> assertEquals("asset_1", change.oldAssetId()),
                    () -> assertNull(change.newAssetId()));
        }

        @Test
        @DisplayName("moved: same source, different position, duration untouched")
        void moved() {
            Project before = project(video("track_001",
                    placedAt("clip_001", "e1", "asset_1", "track_001", 0, 50, 200)));
            Project after = project(video("track_001",
                    placedAt("clip_001", "e1", "asset_1", "track_001", 100, 50, 200)));

            ClipChange change = only(SERVICE.compare(state("a", before), state("b", after))
                    .changes().clipChanges().toArray(new ClipChange[0]));

            assertAll(
                    () -> assertEquals(ChangeType.MOVED, change.type()),
                    () -> assertEquals(Set.of(ChangeType.MOVED), change.kinds()),
                    () -> assertEquals(0L, change.oldStart()),
                    () -> assertEquals(100L, change.newStart()),
                    // A move must not read as a trim
                    () -> assertEquals(50L, change.oldDuration()),
                    () -> assertEquals(50L, change.newDuration()),
                    () -> assertEquals(200L, change.oldSourceIn()),
                    () -> assertEquals(200L, change.newSourceIn()));
        }

        @Test
        @DisplayName("trimmed: shorter on the timeline and in the source together")
        void trimmed() {
            Project before = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50, 0, 49)));
            Project after = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 30, 0, 29)));

            ClipChange change = only(SERVICE.compare(state("a", before), state("b", after))
                    .changes().clipChanges().toArray(new ClipChange[0]));

            assertAll(
                    () -> assertEquals(ChangeType.TRIMMED, change.type()),
                    () -> assertEquals(Set.of(ChangeType.TRIMMED), change.kinds()),
                    () -> assertEquals(0L, change.oldStart()),
                    () -> assertEquals(0L, change.newStart()),
                    () -> assertEquals(50L, change.oldDuration()),
                    () -> assertEquals(30L, change.newDuration()),
                    () -> assertEquals(0L, change.oldSourceIn()),
                    () -> assertEquals(29L, change.newSourceOut()));
        }

        @Test
        @DisplayName("track movement is one change, not a removal and an addition")
        void movedBetweenTracks() {
            Project before = project(
                    video("track_001", clip("clip_001", "e1", "asset_1", "track_001", 0, 50)),
                    com.vvcs.diff.DiffFixtures.audio("track_002"));
            Project after = project(
                    video("track_001"),
                    com.vvcs.diff.DiffFixtures.audio("track_002",
                            clip("clip_001", "e1", "asset_1", "track_002", 0, 50)));

            TimelineComparison comparison = SERVICE.compare(state("a", before), state("b", after));
            ClipChange change = only(comparison.changes().clipChanges().toArray(new ClipChange[0]));

            assertAll(
                    () -> assertEquals(1, comparison.changes().clipChanges().size()),
                    () -> assertEquals(ChangeType.TRACK_CHANGED, change.type()),
                    () -> assertEquals(Set.of(ChangeType.TRACK_CHANGED), change.kinds()),
                    () -> assertEquals("track_001", change.oldTrackId()),
                    () -> assertEquals("track_002", change.newTrackId()),
                    // The frames are untouched, so this is not also a move
                    () -> assertEquals(change.oldStart(), change.newStart()),
                    () -> assertEquals(change.oldDuration(), change.newDuration()));
        }

        @Test
        @DisplayName("reordering reports both positions, and a swap moves both clips")
        void reordered() {
            Project before = DiffFixtures.twoClipsInOrder();
            Project after = DiffFixtures.twoClipsReordered();

            TimelineComparison comparison = SERVICE.compare(state("a", before), state("b", after));
            List<ClipChange> changes = comparison.changes().clipChanges();

            // Swapping two clips retimes both of them, so the engine reports two changes
            // rather than one. Each side of each change is what a consumer draws.
            assertEquals(2, changes.size());
            assertTrue(changes.stream().allMatch(c -> c.kinds().contains(ChangeType.REORDERED)));
            assertTrue(changes.stream().allMatch(c -> c.oldOrder() != c.newOrder()));

            // The two occurrences traded places: one went from the head of the track to second
            // and the other the other way round. Both are visible in the changes alone.
            assertEquals(Set.of(0, 1), changes.stream().map(ClipChange::oldOrder)
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(0, 1), changes.stream().map(ClipChange::newOrder)
                    .collect(java.util.stream.Collectors.toSet()));
        }

        @Test
        @DisplayName("a move and a trim together is one change carrying both kinds")
        void movedAndTrimmed() {
            Project before = project(video("track_001",
                    placedAt("clip_001", "e1", "asset_1", "track_001", 0, 50, 200)));
            Project after = project(video("track_001",
                    placedAt("clip_001", "e1", "asset_1", "track_001", 100, 30, 200)));

            ClipChange change = only(SERVICE.compare(state("a", before), state("b", after))
                    .changes().clipChanges().toArray(new ClipChange[0]));

            assertAll(
                    () -> assertEquals(2, change.kinds().size()),
                    () -> assertTrue(change.kinds().contains(ChangeType.MOVED)),
                    () -> assertTrue(change.kinds().contains(ChangeType.TRIMMED)),
                    () -> assertEquals(0L, change.oldStart()),
                    () -> assertEquals(100L, change.newStart()),
                    () -> assertEquals(50L, change.oldDuration()),
                    () -> assertEquals(30L, change.newDuration()));
        }

        @Test
        @DisplayName("kinds keep the engine's order, so output is reproducible")
        void kindsOrderIsStable() {
            Project before = project(video("track_001",
                    placedAt("clip_001", "e1", "asset_1", "track_001", 0, 50, 200)));
            Project after = project(video("track_001",
                    placedAt("clip_001", "e1", "asset_1", "track_001", 100, 30, 200)));

            List<ClipChange> first =
                    SERVICE.compare(state("a", before), state("b", after)).changes().clipChanges();
            // Built repeatedly, because Set.copyOf randomises iteration order per run and this
            // is the kind of defect that only shows up when output is compared byte for byte.
            for (int i = 0; i < 50; i++) {
                assertEquals(List.copyOf(first),
                        SERVICE.compare(state("a", before), state("b", after))
                                .changes().clipChanges());
            }
            assertEquals(List.of(ChangeType.MOVED, ChangeType.TRIMMED), List.copyOf(
                    first.get(0).kinds()));
        }
    }

    @Nested
    @DisplayName("timeline and track facts")
    class Structure {

        @Test
        @DisplayName("durations are project frames, on the comparison and in each change")
        void durations() {
            Project before = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
            Project after = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 80)));

            TimelineComparison comparison = SERVICE.compare(state("a", before), state("b", after));

            assertAll(
                    () -> assertEquals(50L, comparison.fromDuration()),
                    () -> assertEquals(80L, comparison.toDuration()),
                    () -> assertEquals(50L, comparison.before().duration()),
                    () -> assertEquals(80L, comparison.after().duration()));
        }

        @Test
        @DisplayName("a new track is reported, and appears in the after snapshot")
        void trackAdded() {
            Project before = project(video("track_001",
                    clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
            Project after = project(
                    video("track_001", clip("clip_001", "e1", "asset_1", "track_001", 0, 50)),
                    com.vvcs.diff.DiffFixtures.audio("track_002",
                            clip("clip_002", "e2", "asset_1", "track_002", 0, 50)));

            TimelineComparison comparison = SERVICE.compare(state("a", before), state("b", after));

            assertAll(
                    () -> assertEquals(1, comparison.changes().trackChanges().size()),
                    () -> assertEquals(2, comparison.after().trackCount()),
                    () -> assertEquals(1, comparison.before().trackCount()),
                    () -> assertNotNull(comparison.after().track("track_002")),
                    () -> assertNull(comparison.before().track("track_002")));
        }

        @Test
        @DisplayName("a clip's source range is in the asset's own frames, not the timeline's")
        void sourceCoordinatesAreDistinct() {
            // A timeline frame and a source frame are the same number only by accident. Here the
            // clip sits at 100 on a 30fps timeline and reads source frames 500..549 of a
            // different asset; a consumer that confused the two would place the clip wrongly.
            Clip placed = new Clip("clip_001", "asset_1", "track_001",
                    new com.vvcs.model.SourceRange(500, 549, 50, new com.vvcs.model.Rational(24, 1)),
                    new com.vvcs.model.TimeRange(100, 50),
                    "e1");

            ClipSnapshot snapshot = SERVICE.snapshot(
                    state("s", project(video("track_001", placed))))
                    .tracks().get(0).clips().get(0);

            assertAll(
                    // timeline coordinates, at the project's 30fps
                    () -> assertEquals(100L, snapshot.start()),
                    () -> assertEquals(50L, snapshot.duration()),
                    // source coordinates, at the asset's own 24fps
                    () -> assertEquals(500L, snapshot.sourceIn()),
                    () -> assertEquals(549L, snapshot.sourceOut()),
                    () -> assertEquals(new com.vvcs.model.Rational(24, 1), snapshot.sourceFrameRate()),
                    // the source range is inclusive, so 500..549 is fifty frames
                    () -> assertEquals(50L, snapshot.sourceDuration()));
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("a comparison without both timelines is rejected rather than half built")
        void requiresBothSides() {
            TimelineSnapshot snapshot = SERVICE.snapshot(state("s", DiffFixtures.twoClipsInOrder()));
            TimelineDiff diff = new TimelineDiff("a", "b", List.of(), List.of(), List.of(), 100, 100);

            assertAll(
                    () -> assertThrowsIllegalArgument(() -> new TimelineComparison(null, null,
                            null, snapshot, diff)),
                    () -> assertThrowsIllegalArgument(() -> new TimelineComparison(null, null,
                            snapshot, null, diff)),
                    () -> assertThrowsIllegalArgument(() -> new TimelineComparison(null, null,
                            snapshot, snapshot, null)));
        }

        @Test
        @DisplayName("a snapshot's lists cannot be modified by a consumer")
        void snapshotsAreImmutable() {
            TimelineSnapshot snapshot = SERVICE.snapshot(state("s", DiffFixtures.twoClipsInOrder()));

            assertThrowsUnsupported(() -> snapshot.tracks().clear());
            assertThrowsUnsupported(() -> snapshot.tracks().get(0).clips().clear());
        }
    }

    private static void assertThrowsIllegalArgument(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, action::run);
    }

    private static void assertThrowsUnsupported(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, action::run);
    }
}
