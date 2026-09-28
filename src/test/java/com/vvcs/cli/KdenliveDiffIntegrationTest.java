package com.vvcs.cli;

import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;
import com.vvcs.model.SourceRange;
import com.vvcs.model.TimeRange;
import com.vvcs.model.TimeSpan;
import com.vvcs.model.Timeline;
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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vvcs diff} against the real sample project: two parses of the same file, then the
 * same file with one clip trimmed, so that the whole path from XML to reported change is
 * exercised rather than a hand-built model.
 */
class KdenliveDiffIntegrationTest {

    private static final Path SAMPLE = Path.of("sample-project/sample.kdenlive");
    private static final String AUTHOR = "editor@example.com";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"),
            ZoneOffset.UTC);

    @Test
    @DisplayName("diffing a commit against itself reports no changes")
    void aCommitAgainstItselfIsUnchanged(@TempDir Path work) throws Exception {
        runCli(work, "init", "--author", AUTHOR);
        commitSample(work, "the project as it is");

        Result result = runCli(work, "diff", head(work), head(work));

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("no changes"), result.out());
    }

    @Test
    @DisplayName("shortening a clip in a real project is reported as a trim, not a rewrite")
    void aTrimmedClipIsReportedAsATrim(@TempDir Path work) throws Exception {
        runCli(work, "init", "--author", AUTHOR);
        commitSample(work, "the project as it is");

        // Shorten the first video clip, which is the one entry that plays chain1.
        String edited = trimFirstVideoClip(Files.readString(SAMPLE));
        assertNotEquals(Files.readString(SAMPLE), edited, "the sample should have been edited");
        Path project = writeProject(work, edited);

        Repository repository = new FileSystemRepository(work, CLOCK);
        Project parsed = new KdenliveParser().parse(project);
        new CommitWorkflow(repository, CLOCK)
                .commit(parsed, "trimmed the first video clip", AUTHOR);
        String second = head(work);

        Result result = runCli(work, "diff", firstCommit(work), second);

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("trimmed"), result.out());
        assertTrue(result.out().contains("length"), result.out());
        assertTrue(result.out().contains("timeline"), result.out());
    }

    @Test
    @DisplayName("adding a clip to a real project is reported as an addition, and nothing else")
    void anAddedClipIsReportedAsAnAddition(@TempDir Path work) throws Exception {
        runCli(work, "init", "--author", AUTHOR);
        commitSample(work, "the project as it is");

        // Prepend a new entry to the timeline playlist, reusing an existing producer, which is
        // what inserting a clip in the editor does.
        String edited = Files.readString(SAMPLE).replace(
                "<playlist id=\"playlist6\">",
                "<playlist id=\"playlist6\">"
                        + "<entry in=\"00:00:00.000\" out=\"00:00:05.000\" producer=\"chain4\">"
                        + "<property name=\"kdenlive:id\">99</property></entry>");
        assertNotEquals(Files.readString(SAMPLE), edited, "the sample should have been edited");
        Path project = writeProject(work, edited);

        Repository repository = new FileSystemRepository(work, CLOCK);
        Project parsed = new KdenliveParser().parse(project);
        new CommitWorkflow(repository, CLOCK)
                .commit(parsed, "added a clip at the head of the video track", AUTHOR);
        String second = head(work);

        Result result = runCli(work, "diff", firstCommit(work), second);

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("added"), result.out());
        // The clips that were already there keep their identity and are only reported as
        // having moved along, which is the whole point of deriving identity from the editor's
        // own identifier rather than from the order the parser walks them in.
        assertTrue(result.out().contains("moved"), result.out());
    }

    @Test
    @DisplayName("every clip in the real sample carries the editor's own identifier")
    void everyRealClipCarriesAnIdentifier() throws Exception {
        Project project = new KdenliveParser().parse(SAMPLE);

        List<Clip> clips = project.timeline().tracks().stream()
                .flatMap(track -> track.clips().stream())
                .toList();
        assertTrue(clips.size() >= 5, "the sample has at least five placements");
        for (Clip clip : clips) {
            assertTrue(clip.sourceId() != null && !clip.sourceId().isBlank(),
                    "clip " + clip.id() + " should carry a kdenlive:id");
        }
    }

    @Test
    @DisplayName("parsing the same file twice gives the same identity for every clip")
    void reparsingIsStable() throws Exception {
        Project first = new KdenliveParser().parse(SAMPLE);
        Project second = new KdenliveParser().parse(SAMPLE);

        assertEquals(first, second, "the parse of an unchanged file is unchanged");
    }

    @Test
    @DisplayName("a real project diffed against itself produces no clip changes")
    void aRealProjectAgainstItselfIsUnchanged() throws Exception {
        Project project = new KdenliveParser().parse(SAMPLE);

        var diff = new com.vvcs.diff.DiffEngine().diff(project, project);

        assertTrue(diff.isEmpty());
        assertTrue(diff.clipChanges().isEmpty());
    }

    @Test
    @DisplayName("diff needs two commits, and says so")
    void diffRefusesTheWrongNumberOfCommits(@TempDir Path work) throws Exception {
        runCli(work, "init", "--author", AUTHOR);
        commitSample(work, "the project as it is");

        Result none = runCli(work, "diff");
        Result one = runCli(work, "diff", head(work));

        assertEquals(2, none.exitCode());
        assertTrue(none.err().contains("needs two commits"), none.err());
        assertEquals(2, one.exitCode());
    }

    @Test
    @DisplayName("diff of an unknown commit is reported as a failure, not a crash")
    void diffOfAnUnknownCommitFails(@TempDir Path work) throws Exception {
        runCli(work, "init", "--author", AUTHOR);
        commitSample(work, "the project as it is");

        Result result = runCli(work, "diff", "0000", head(work));

        assertEquals(1, result.exitCode());
        assertTrue(result.err().startsWith("vvcs:"), result.err());
    }

    @Test
    @DisplayName("diff works in the reverse direction too")
    void diffIsReversible(@TempDir Path work) throws Exception {
        runCli(work, "init", "--author", AUTHOR);
        commitSample(work, "the project as it is");
        commitSample(work, "committed the same project again");

        Result result = runCli(work, "diff", head(work), firstCommit(work));

        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("no changes"),
                "the same project committed twice is still the same project: " + result.out());
    }

    // ------------------------------------------------------------------ helpers

    private void commitSample(Path work, String message) throws IOException {
        Path project = work.resolve("sample.kdenlive");
        Files.writeString(project, Files.readString(SAMPLE));
        runCli(work, "commit", "-m", message);
    }

    private Path writeProject(Path work, String contents) throws IOException {
        Path project = work.resolve("edited.kdenlive");
        Files.writeString(project, contents);
        return project;
    }

    private String head(Path work) {
        return new FileSystemRepository(work, CLOCK).head().orElseThrow().shortId();
    }

    private String firstCommit(Path work) {
        return new FileSystemRepository(work, CLOCK).history(head(work)).get(1).shortId();
    }

    private Result runCli(Path work, String... args) {
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

    /**
     * Guards the assumption the trim test rests on: that the edited sample really does parse
     * to one clip shorter, so a passing assertion means what it claims.
     */
    @Test
    @DisplayName("the edited sample really is shorter than the original")
    void theEditedSampleIsShorter() throws Exception {
        String edited = trimFirstVideoClip(Files.readString(SAMPLE));
        assertNotEquals(Files.readString(SAMPLE), edited);

        long originalFrames = totalFrames(new KdenliveParser().parse(SAMPLE));
        long editedFrames = totalFrames(new KdenliveParser().parse(writeProject(
                Files.createTempDirectory("vvcs"), edited)));

        assertTrue(editedFrames < originalFrames,
                "the edited project should be shorter: " + editedFrames + " vs " + originalFrames);
    }

    /**
     * Shortens the out point of the one entry that plays {@code chain1}. The entry is found
     * by its producer and its out point rewritten inside that entry, so the test depends on
     * neither the attribute order nor the exact text the sample happens to use.
     */
    private static String trimFirstVideoClip(String xml) {
        Matcher entry = Pattern.compile("<entry[^>]*producer=\"chain1\"[^>]*>").matcher(xml);
        if (!entry.find()) {
            return xml;
        }
        String trimmed = entry.group().replaceFirst("out=\"[^\"]*\"", "out=\"00:00:10.677\"");
        return xml.substring(0, entry.start()) + trimmed + xml.substring(entry.end());
    }

    private static long totalFrames(Project project) {
        return project.timeline().tracks().stream()
                .mapToLong(track -> track.clips().stream()
                        .mapToLong(clip -> clip.timeline().duration()).sum())
                .sum();
    }
}
