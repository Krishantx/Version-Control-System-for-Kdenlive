package com.vvcs.cli;

import com.vvcs.comparison.TimelineComparison;
import com.vvcs.serialization.VvcsJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison as the command line hands it over.
 *
 * <p>{@code diff} was the first consumer of a comparison and is now driven by the same service
 * anything else would use, so these tests are about the command's own job: that it accepts the
 * option, that it emits a single parseable document rather than a report with a report in it,
 * and that the human output is unchanged by any of this.
 */
class VvcsCliComparisonTest {

    private static final Path SAMPLE = Path.of("sample-project/sample.kdenlive");
    private static final String AUTHOR = "editor@example.com";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"),
            ZoneOffset.UTC);

    @Test
    @DisplayName("the usage text advertises the option")
    void optionIsDocumented() {
        Result result = run(Path.of("."), "help");

        assertTrue(result.out().contains("--json"), result.out());
    }

    @Test
    @DisplayName("diff --json emits one parseable comparison and nothing else")
    void jsonIsAParseableDocument(@TempDir Path work) {
        twoCommits(work);

        Result result = run(work, "diff", commitAt(work, 2), commitAt(work, 1), "--json");

        assertEquals(0, result.exitCode(), result.err());
        TimelineComparison comparison = parse(result.out());
        assertAll(
                // The sample parses to five placements, four of which an editor sees as
                // clips, one of them a split audio/video pair
                () -> assertEquals(5, comparison.before().clipCount()),
                () -> assertEquals(5, comparison.after().clipCount()),
                () -> assertTrue(comparison.changes().clipChanges().size() >= 1));
    }

    @Test
    @DisplayName("the option works before the commits and in its short form")
    void optionIsAcceptedInAnyPosition(@TempDir Path work) {
        twoCommits(work);
        String a = commitAt(work, 2);
        String b = commitAt(work, 1);

        Result trailing = run(work, "diff", a, b, "--json");
        Result leading = run(work, "diff", "--json", a, b);
        Result shortForm = run(work, "diff", a, b, "-j");

        assertAll(
                () -> assertEquals(0, trailing.exitCode(), trailing.err()),
                () -> assertEquals(0, leading.exitCode(), leading.err()),
                () -> assertEquals(0, shortForm.exitCode(), shortForm.err()),
                () -> assertEquals(trailing.out(), leading.out()),
                () -> assertEquals(trailing.out(), shortForm.out()));
    }

    @Test
    @DisplayName("two runs of the same comparison give the same bytes")
    void outputIsDeterministic(@TempDir Path work) {
        twoCommits(work);
        String a = commitAt(work, 2);
        String b = commitAt(work, 1);

        String first = run(work, "diff", a, b, "--json").out();
        for (int i = 0; i < 10; i++) {
            assertEquals(first, run(work, "diff", a, b, "--json").out(),
                    "the JSON a consumer caches must not change between identical requests");
        }
    }

    @Test
    @DisplayName("the JSON carries the commit labels, not just the timelines")
    void jsonCarriesCommitLabels(@TempDir Path work) {
        twoCommits(work);

        String json = run(work, "diff", commitAt(work, 2), commitAt(work, 1), "--json").out();

        assertAll(
                () -> assertTrue(json.contains("the first version"), json),
                () -> assertTrue(json.contains("trimmed the first clip"), json),
                () -> assertTrue(json.contains(AUTHOR), json));
    }

    @Test
    @DisplayName("an unchanged pair still returns both timelines as JSON")
    void unchangedPairStillHasBothSides(@TempDir Path work) {
        run(work, "init", "--author", AUTHOR);
        commitSample(work, "the first version");
        commitSample(work, "the same project again");

        Result result = run(work, "diff", commitAt(work, 2), commitAt(work, 1), "--json");
        TimelineComparison comparison = parse(result.out());

        assertAll(
                () -> assertEquals(0, result.exitCode(), result.err()),
                () -> assertTrue(comparison.isEmpty()),
                () -> assertTrue(comparison.before() != null),
                () -> assertTrue(comparison.after() != null));
    }

    @Test
    @DisplayName("without the option the output is still the human report")
    void humanOutputIsUnchanged(@TempDir Path work) {
        twoCommits(work);

        Result human = run(work, "diff", commitAt(work, 2), commitAt(work, 1));
        Result json = run(work, "diff", commitAt(work, 2), commitAt(work, 1), "--json");

        assertAll(
                () -> assertTrue(human.out().contains("trimmed"), human.out()),
                () -> assertTrue(human.out().contains("diff "), human.out()),
                // The human report is prose; the JSON is not
                () -> assertTrue(!human.out().trim().startsWith("{"), human.out()),
                () -> assertTrue(json.out().trim().startsWith("{"), json.out()));
    }

    @Test
    @DisplayName("an unknown commit is refused with the same message either way")
    void unknownCommitIsRefused(@TempDir Path work) {
        twoCommits(work);

        Result human = run(work, "diff", "deadbeef", commitAt(work, 1));
        Result json = run(work, "diff", "deadbeef", commitAt(work, 1), "--json");

        assertAll(
                () -> assertEquals(1, human.exitCode()),
                () -> assertEquals(1, json.exitCode()),
                () -> assertTrue(json.err().startsWith("vvcs:"), json.err()),
                () -> assertEquals(human.err(), json.err()));
    }

    @Test
    @DisplayName("diff still needs two commits")
    void stillNeedsTwoCommits(@TempDir Path work) {
        twoCommits(work);

        Result json = run(work, "diff", commitAt(work, 1), "--json");

        assertEquals(2, json.exitCode());
        assertTrue(json.err().contains("two commits"), json.err());
    }

    // -------------------------------------------------------------------------------------

    private TimelineComparison parse(String out) {
        try {
            return VvcsJson.read(out, TimelineComparison.class);
        } catch (IOException e) {
            throw new UncheckedIOException("not a parseable comparison: " + out, e);
        }
    }

    private void twoCommits(Path work) {
        run(work, "init", "--author", AUTHOR);
        commitSample(work, "the first version");
        commitEditedSample(work, "trimmed the first clip");
    }

    private void commitSample(Path work, String message) {
        try {
            Files.writeString(work.resolve("sample.kdenlive"), Files.readString(SAMPLE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        run(work, "commit", "-m", message);
    }

    /** Shortens the sample's first standalone video clip, leaving the file on disk alone. */
    private void commitEditedSample(Path work, String message) {
        var repository = new com.vvcs.repository.FileSystemRepository(work, CLOCK);
        var parsed = new com.vvcs.parser.KdenliveParser().parse(work.resolve("sample.kdenlive"));
        new com.vvcs.workflow.CommitWorkflow(repository, CLOCK)
                .commit(withTrimmedClip(parsed), message, AUTHOR);
    }

    private com.vvcs.model.Project withTrimmedClip(com.vvcs.model.Project project) {
        var tracks = new java.util.ArrayList<com.vvcs.model.Track>();
        for (var track : project.timeline().tracks()) {
            var clips = new java.util.ArrayList<com.vvcs.model.Clip>();
            boolean trimmed = false;
            for (var clip : track.clips()) {
                boolean isAvPairHalf = project.timeline().tracks().stream()
                        .flatMap(t -> t.clips().stream())
                        .anyMatch(other -> !other.id().equals(clip.id())
                                && other.sourceId().equals(clip.sourceId()));
                if (!trimmed && track.type() == com.vvcs.model.TrackType.VIDEO
                        && !isAvPairHalf) {
                    trimmed = true;
                    long newDuration = 100;
                    long newOut = clip.source().inFrame() + newDuration - 1;
                    clips.add(new com.vvcs.model.Clip(clip.id(), clip.assetId(), track.id(),
                            new com.vvcs.model.SourceRange(clip.source().inFrame(), newOut,
                                    newDuration, clip.source().timebase()),
                            new com.vvcs.model.TimeRange(clip.timeline().start(), newDuration),
                            clip.sourceId()));
                } else {
                    clips.add(clip);
                }
            }
            tracks.add(new com.vvcs.model.Track(track.id(), track.type(), clips));
        }
        return new com.vvcs.model.Project(project.name(), project.resolution(), project.frameRate(),
                project.assets(),
                new com.vvcs.model.Timeline(project.timeline().id(), project.timeline().name(),
                        project.frameRate(), project.resolution(),
                        project.timeline().duration(), tracks));
    }

    private String commitAt(Path work, int position) {
        var repository = new com.vvcs.repository.FileSystemRepository(work, CLOCK);
        var ids = new java.util.ArrayList<String>();
        for (var commit : repository.history(repository.head().orElseThrow().id())) {
            ids.add(commit.shortId());
            if (ids.size() == position) {
                break;
            }
        }
        return ids.get(position - 1);
    }

    private Result run(Path work, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new VvcsCli(work, CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String out, String err) {
    }
}
