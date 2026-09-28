package com.vvcs.comparison;

import com.vvcs.diff.ChangeType;
import com.vvcs.diff.DiffFixtures;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import com.vvcs.serialization.VvcsJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.vvcs.diff.DiffFixtures.asset;
import static com.vvcs.diff.DiffFixtures.clip;
import static com.vvcs.diff.DiffFixtures.placedAt;
import static com.vvcs.diff.DiffFixtures.project;
import static com.vvcs.diff.DiffFixtures.video;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison as it leaves the process.
 *
 * <p>A consumer that receives JSON cannot ask a follow-up question, so these tests check two
 * separate promises: that the text is stable enough to diff and cache, and that reading it
 * back yields the same object.
 */
class TimelineComparisonJsonTest {

    private static final TimelineComparisonService SERVICE = new TimelineComparisonService(null);

    private static ProjectState state(String id, Project project) {
        return new ProjectState(id, project);
    }

    /** A comparison exercising clips, tracks, durations, source ranges and commit labels. */
    private static TimelineComparison mixed() {
        Project before = project(
                video("track_001",
                        clip("clip_001", "e1", "asset_1", "track_001", 0, 50, 0, 49),
                        clip("clip_002", "e2", "asset_2", "track_001", 50, 50, 100, 149)),
                DiffFixtures.audio("track_002",
                        clip("clip_003", "e3", "asset_1", "track_002", 0, 100, 0, 99)));
        Project after = project(
                video("track_001",
                        placedAt("clip_001", "e1", "asset_1", "track_001", 0, 30, 0),
                        clip("clip_002", "e2", "asset_2", "track_001", 30, 50, 100, 149)),
                DiffFixtures.audio("track_002",
                        clip("clip_003", "e3", "asset_1", "track_002", 0, 100, 0, 99)));

        return new TimelineComparisonService(null).compare(
                new CommitRef("aaaaaaa1", "aaaaaaa", "state_1", "Ada", "first cut",
                        java.time.Instant.parse("2026-01-02T03:04:05Z")),
                state("state_1", before),
                new CommitRef("bbbbbbb2", "bbbbbbb", "state_2", "Ada", "tighter",
                        java.time.Instant.parse("2026-01-03T03:04:05Z")),
                state("state_2", after));
    }

    @Test
    @DisplayName("a comparison round-trips through JSON unchanged")
    void roundTrips() throws IOException {
        TimelineComparison original = mixed();

        String json = VvcsJson.toJson(original);
        TimelineComparison parsed = VvcsJson.read(json, TimelineComparison.class);

        assertEquals(original, parsed);
    }

    @Test
    @DisplayName("serializing the same comparison twice gives the same bytes")
    void isDeterministic() throws IOException {
        TimelineComparison comparison = mixed();

        // Repeated because the failure this guards against is a set whose iteration order is
        // randomised per run, which passes a single comparison and fails a diff between
        // yesterday's cached response and today's.
        for (int i = 0; i < 25; i++) {
            assertEquals(VvcsJson.toJson(comparison), VvcsJson.toJson(mixed()));
        }
    }

    @Test
    @DisplayName("the text names the three parts a consumer needs and nothing internal")
    void shape() throws IOException {
        String json = VvcsJson.toJson(mixed());

        assertAllPresent(json,
                "\"before\"", "\"after\"", "\"changes\"", "\"tracks\"", "\"clips\"",
                "\"frameRate\"", "\"resolution\"", "\"duration\"", "\"stateId\"",
                "\"assetName\"", "\"start\"", "\"sourceIn\"", "\"sourceOut\"",
                "\"clipChanges\"", "\"trackChanges\"", "\"assetChanges\"",
                "\"kinds\"", "\"type\"");
        // The edit vocabulary is preserved, not flattened to a boolean
        assertTrue(json.contains("TRIMMED"), "the change type survives serialization");
    }

    @Test
    @DisplayName("the text leaks no editor, producer or repository detail")
    void leaksNothingInternal() throws IOException {
        String json = VvcsJson.toJson(mixed());

        // An asset's *name* is meant to be here, so that a consumer can label a clip with the
        // media it plays. Where that name came from is not: paths, editor vocabulary and
        // repository layout all stay on this side of the boundary.
        for (String forbidden : List.of("kdenlive", "producer", "playlist",
                "/media/", ".kdenlive", "state_1/", "repository", "parentId",
                "absolutePath", "sha256")) {
            assertFalse(json.contains(forbidden),
                    "the response should not mention " + forbidden + ", but it did: " + json);
        }
        assertTrue(json.contains("asset_1.mp4"), "the asset name is meant to be in the response");
    }

    @Test
    @DisplayName("both timelines are in the text even when nothing changed")
    void identicalStatesStillSerializeBothSides() throws IOException {
        Project project = DiffFixtures.twoClipsInOrder();
        TimelineComparison comparison =
                SERVICE.compare(state("state_1", project), state("state_1", project));

        String json = VvcsJson.toJson(comparison);

        assertAllPresent(json, "\"before\"", "\"after\"", "\"clipChanges\" : [ ]", "\"tracks\"");
        assertEquals(comparison, VvcsJson.read(json, TimelineComparison.class));
    }

    @Test
    @DisplayName("a fractional frame rate survives the round trip exactly")
    void fractionalFrameRateSurvives() throws IOException {
        // 24000/1001 must not come back as 24: a timeline measured in whole frames instead of
        // its own rate drifts, and by 1001 frames per thousand seconds it is visibly wrong.
        var ntsc = new com.vvcs.model.Rational(24_000, 1_001);
        Project project = new Project("demo", DiffFixtures.HD, ntsc,
                List.of(asset("asset_1")),
                new com.vvcs.model.Timeline("timeline_1", "Main", ntsc, DiffFixtures.HD, 1_001,
                        List.of(video("track_001",
                                clip("clip_001", "e1", "asset_1", "track_001", 0, 50)))));

        TimelineComparison comparison = SERVICE.compare(state("s", project), state("s", project));
        TimelineComparison parsed =
                VvcsJson.read(VvcsJson.toJson(comparison), TimelineComparison.class);

        assertAll(
                () -> assertEquals(ntsc, parsed.before().frameRate()),
                () -> assertEquals(ntsc, parsed.after().frameRate()),
                () -> assertEquals(24_000L, parsed.before().frameRate().numerator()),
                () -> assertEquals(1_001L, parsed.before().frameRate().denominator()));
    }

    @Test
    @DisplayName("a 24fps timeline over a 30fps asset keeps the two rates apart")
    void sourceRateIsNotTheTimelineRate() throws IOException {
        // The clip fixture reads its source at 30fps. The timeline it sits on is 23.976. Both
        // rates are in the response, and the source range is expressed in the asset's, so a
        // consumer cannot read a source frame as if it were a timeline frame.
        var ntsc = new com.vvcs.model.Rational(24_000, 1_001);
        var sourceRate = new com.vvcs.model.Rational(30, 1);
        Project project = new Project("demo", DiffFixtures.HD, ntsc,
                List.of(asset("asset_1")),
                new com.vvcs.model.Timeline("timeline_1", "Main", ntsc, DiffFixtures.HD, 1_001,
                        List.of(video("track_001", new com.vvcs.model.Clip("clip_001", "asset_1",
                                "track_001",
                                new com.vvcs.model.SourceRange(500, 549, 50, sourceRate),
                                new com.vvcs.model.TimeRange(100, 50), "e1")))));

        TimelineComparison parsed = VvcsJson.read(
                VvcsJson.toJson(SERVICE.compare(state("s", project), state("s", project))),
                TimelineComparison.class);
        ClipSnapshot clip = parsed.before().tracks().get(0).clips().get(0);

        assertAll(
                () -> assertEquals(ntsc, parsed.before().frameRate()),
                () -> assertEquals(sourceRate, clip.sourceFrameRate()),
                // timeline position at 23.976, source position in the 30fps asset
                () -> assertEquals(100L, clip.start()),
                () -> assertEquals(500L, clip.sourceIn()),
                () -> assertEquals(549L, clip.sourceOut()));
    }

    @Test
    @DisplayName("a round trip preserves every change kind")
    void changeKindsSurvive() throws IOException {
        TimelineComparison comparison = mixed();

        TimelineComparison parsed =
                VvcsJson.read(VvcsJson.toJson(comparison), TimelineComparison.class);

        assertFalse(comparison.changes().clipChanges().isEmpty());
        assertEquals(ChangeType.TRIMMED, parsed.changes().clipChanges().get(0).type());
        assertEquals(comparison.changes().clipChanges().get(0).kinds(),
                parsed.changes().clipChanges().get(0).kinds());
    }

    @Test
    @DisplayName("the flat views survive too, so no consumer has to unpack before/after")
    void flatViewsSurvive() throws IOException {
        TimelineComparison comparison = mixed();
        var original = comparison.changes().clipChanges().get(0);

        var parsed = VvcsJson.read(VvcsJson.toJson(comparison), TimelineComparison.class)
                .changes().clipChanges().get(0);

        assertNotNull(parsed.before());
        assertNotNull(parsed.after());
        assertEquals(original.oldStart(), parsed.before().timelineStart());
        assertEquals(original.newDuration(), parsed.after().timelineDuration());
    }

    @Test
    @DisplayName("derived accessors stay out of the text")
    void derivedAccessorsAreNotSerialized() throws IOException {
        // Jackson treats a no-argument isX() as a property, so isEmpty() would otherwise be
        // stored beside the very lists it is derived from. A consumer reading it could believe
        // a comparison with no changes while the lists beside it disagree.
        String json = VvcsJson.toJson(mixed());

        for (String derived : List.of("\"empty\"", "\"end\"", "\"sourceDuration\"",
                "\"clipCount\"", "\"trackCount\"", "\"oldStart\"", "\"newStart\"",
                "\"oldDuration\"", "\"newDuration\"", "\"oldSourceIn\"", "\"newSourceOut\"",
                "\"oldOrder\"", "\"newOrder\"")) {
            assertFalse(json.contains(derived),
                    "the response should not carry the derived property " + derived
                            + ", but it did:\n" + json);
        }
        // The real fields are all still there. clipId in particular is a genuine component of
        // a snapshot and of an occurrence; it is only ClipChange's derived copy that is not
        // sent, because before and after already carry it.
        assertAllPresent(json, "\"clipId\"", "\"start\"", "\"sourceIn\"", "\"kinds\"");
    }

    private static void assertAllPresent(String json, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(json.contains(fragment), "expected the response to contain " + fragment
                    + ", but it did not:\n" + json);
        }
    }
}
