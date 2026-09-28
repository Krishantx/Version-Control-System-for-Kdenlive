package com.vvcs.cli;

import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.SourceRange;
import com.vvcs.model.TimeRange;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;
import com.vvcs.parser.KdenliveParser;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;
import com.vvcs.workflow.CommitWorkflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code diff} command's own behaviour: what it accepts, what it refuses, and what it
 * prints. The substance of the comparison is covered by {@code DiffEngineTest}.
 */
class VvcsCliDiffTest {

    private static final Path SAMPLE = Path.of("sample-project/sample.kdenlive");
    private static final String AUTHOR = "editor@example.com";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"),
            ZoneOffset.UTC);

    @Test
    @DisplayName("diff appears in the usage text")
    void diffIsDocumented() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new VvcsCli(Path.of("."), CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .run(new String[]{"help"});

        String usage = out.toString(StandardCharsets.UTF_8);
        assertTrue(usage.contains("diff <commit-a> <commit-b>"), usage);
    }

    @Test
    @DisplayName("diff is recognised as a command rather than reported as unknown")
    void diffIsAKnownCommand() {
        Result result = run(Path.of("."), "diff");

        // It gets past command dispatch and fails on the arguments, not on the name.
        assertEquals(2, result.exitCode());
        assertTrue(!result.err().contains("unknown command"), result.err());
    }

    @Test
    @DisplayName("diff with no arguments is a usage error naming what is needed")
    void diffWithoutArgumentsExplainsItself() {
        Result result = run(Path.of("."), "diff");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("vvcs diff <commit-a> <commit-b>"), result.err());
    }

    @Test
    @DisplayName("diff with one commit is a usage error")
    void diffWithOneCommitIsRefused() {
        Result result = run(Path.of("."), "diff", "abc123");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("needs two commits"), result.err());
    }

    @Test
    @DisplayName("diff with three commits is a usage error")
    void diffWithThreeCommitsIsRefused() {
        Result result = run(Path.of("."), "diff", "a", "b", "c");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("needs two commits"), result.err());
    }

    @Test
    @DisplayName("diff outside a repository is reported, not thrown")
    void diffOutsideARepositoryIsReported(@TempDir Path work) {
        Result result = run(work, "diff", "a", "b");

        assertEquals(1, result.exitCode());
        assertTrue(result.err().startsWith("vvcs:"), result.err());
    }

    @Test
    @DisplayName("the report names both commits and their messages")
    void theReportNamesBothCommits(@TempDir Path work) {
        run(work, "init", "--author", AUTHOR);
        commitSample(work, "the first version");
        commitEditedSample(work, "trimmed the first clip");

        Result result = run(work, "diff", commitAt(work, 2), commitAt(work, 1));

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("the first version"), result.out());
        assertTrue(result.out().contains("trimmed the first clip"), result.out());
        assertTrue(result.out().contains("diff "), result.out());
    }

    @Test
    @DisplayName("a trim is printed with its before and after")
    void aTrimIsPrintedWithBothSides(@TempDir Path work) {
        run(work, "init", "--author", AUTHOR);
        commitSample(work, "the first version");
        commitEditedSample(work, "trimmed the first clip");

        Result result = run(work, "diff", commitAt(work, 2), commitAt(work, 1));

        assertTrue(result.out().contains("trimmed"), result.out());
        assertTrue(result.out().contains("length " + trimmedFrom + " -> 100"),
                "expected the trim to be reported as " + trimmedFrom + " -> 100: " + result.out());
    }

    @Test
    @DisplayName("an unchanged pair is reported as no changes")
    void anUnchangedPairIsReportedAsSuch(@TempDir Path work) {
        run(work, "init", "--author", AUTHOR);
        commitSample(work, "the first version");
        commitSample(work, "the same project again");

        Result result = run(work, "diff", commitAt(work, 2), commitAt(work, 1));

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("no changes"), result.out());
    }

    @Test
    @DisplayName("the report shows added clips with the track they landed on")
    void anAdditionIsPrintedWithItsTrack(@TempDir Path work) {
        run(work, "init", "--author", AUTHOR);
        commitSample(work, "the first version");
        commitSample(work, "the same project again");

        Repository repository = new FileSystemRepository(work, CLOCK);
        Project parsed = new KdenliveParser().parse(work.resolve("sample.kdenlive"));
        new CommitWorkflow(repository, CLOCK).commit(withExtraClip(parsed), "added a clip", AUTHOR);

        Result result = run(work, "diff", commitAt(work, 2), commitAt(work, 1));

        assertTrue(result.out().contains("added"), result.out());
        assertTrue(result.out().contains("track_"), result.out());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The same project with one extra clip on the first video track, built through the model
     * so that the command is exercised without editing the sample file.
     */
    private Project withExtraClip(Project project) {
        List<Track> tracks = new ArrayList<>();
        boolean[] added = {false}; // effectively final for the lambda-free loop below
        for (Track track : project.timeline().tracks()) {
            if (track.type() == TrackType.VIDEO && !track.isEmpty() && !added[0]) {
                added[0] = true;
                List<Clip> clips = new ArrayList<>(track.clips());
                Clip first = clips.get(0);
                clips.add(0, new Clip("clip_new", first.assetId(), track.id(),
                        new SourceRange(0, 49, 50, first.source().timebase()),
                        new TimeRange(0, 50), "added-by-test"));
                tracks.add(new Track(track.id(), track.type(), clips));
            } else {
                tracks.add(track);
            }
        }
        return new Project(project.name(), project.resolution(), project.frameRate(),
                project.assets(),
                new com.vvcs.model.Timeline(project.timeline().id(),
                        project.timeline().name(), project.frameRate(), project.resolution(),
                        project.timeline().duration(), tracks));
    }

    /** Copies the real sample into the working directory and commits it as the CLI finds it. */
    private void commitSample(Path work, String message) {
        try {
            Files.writeString(work.resolve("sample.kdenlive"), Files.readString(SAMPLE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        run(work, "commit", "-m", message);
    }

    /**
     * Commits a version of the sample whose first video clip is shorter. Done through the
     * model, so the sample file on disk stays untouched.
     */
    private void commitEditedSample(Path work, String message) {
        Repository repository = new FileSystemRepository(work, CLOCK);
        Project parsed = new KdenliveParser().parse(work.resolve("sample.kdenlive"));
        new CommitWorkflow(repository, CLOCK).commit(withTrimmedClip(parsed), message, AUTHOR);
    }

    private Project withTrimmedClip(Project project) {
        List<Track> tracks = new ArrayList<>();
        for (Track track : project.timeline().tracks()) {
            List<Clip> clips = new ArrayList<>();
            boolean trimmed = false;
            for (Clip clip : track.clips()) {
                // The first clip of a video track that has one, which is a plain clip rather
                // than one half of an audio/video pair, is the one shortened here.
                if (!trimmed && track.type() == TrackType.VIDEO && !clip.sourceId().isBlank()
                        && project.timeline().tracks().stream()
                                .flatMap(t -> t.clips().stream())
                                .noneMatch(other -> !other.id().equals(clip.id())
                                        && other.sourceId().equals(clip.sourceId()))) {
                    trimmed = true;
                    trimmedFrom = clip.timeline().duration();
                    long newDuration = 100;
                    long newOut = clip.source().inFrame() + newDuration - 1;
                    clips.add(new Clip(clip.id(), clip.assetId(), track.id(),
                            new SourceRange(clip.source().inFrame(), newOut, newDuration,
                                    clip.source().timebase()),
                            new TimeRange(clip.timeline().start(), newDuration), clip.sourceId()));
                } else {
                    clips.add(clip);
                }
            }
            tracks.add(new Track(track.id(), track.type(), clips));
        }
        return withTracks(project, tracks);
    }

    private long trimmedFrom;

    private Project withTracks(Project project, List<Track> tracks) {
        return new Project(project.name(), project.resolution(), project.frameRate(),
                project.assets(),
                new com.vvcs.model.Timeline(project.timeline().id(),
                        project.timeline().name(), project.frameRate(), project.resolution(),
                        project.timeline().duration(), tracks));
    }

    private List<String> commits(Path work, int count) {
        Repository repository = new FileSystemRepository(work, CLOCK);
        List<String> ids = new ArrayList<>();
        for (var commit : repository.history(repository.head().orElseThrow().id())) {
            ids.add(commit.shortId());
            if (ids.size() == count) {
                break;
            }
        }
        return ids;
    }

    private String commitAt(Path work, int position) {
        return commits(work, position).get(position - 1);
    }

    private Result run(Path work, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new VvcsCli(work, CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Result(code,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String out, String err) {
    }
}
