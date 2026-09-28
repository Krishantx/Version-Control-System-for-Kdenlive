package com.vvcs.cli;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vvcs.model.Commit;
import com.vvcs.model.ProjectState;
import com.vvcs.parser.KdenliveParser;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the vvcs commands over a real Kdenlive project.
 *
 * <p>Nothing here is stubbed. The project is copied to a temporary directory, parsed with the
 * real {@link KdenliveParser}, edited as text and parsed again, so a difference between the
 * two commits comes from the parser rather than from a hand-built model.
 */
class VvcsCliIntegrationTest {

    private static final Path SAMPLE = Paths.get("sample-project", "sample.kdenlive");

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Path directory, String... args) {
        PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8);
        return new VvcsCli(directory, Clock.systemDefaultZone(), stdout, stderr).run(args);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path copySampleTo(Path directory) throws IOException {
        Path copy = directory.resolve("sample.kdenlive");
        Files.createDirectories(directory);
        Files.copy(SAMPLE, copy, StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    // ------------------------------------------------------------- the journey

    @Test
    void initCommitLogCheckoutOverARealProject(@TempDir Path workspace) throws IOException {
        copySampleTo(workspace);

        assertEquals(0, run(workspace, "init"), errors());
        assertTrue(Files.isDirectory(workspace.resolve(".vvcs")));

        assertEquals(0, run(workspace, "commit", "-m", "Initial edit"), errors());
        Repository repository = new FileSystemRepository(workspace);
        Commit first = repository.head().orElseThrow();
        assertAll(
                () -> assertTrue(first.isRoot()),
                () -> assertEquals(64, first.stateId().length(),
                        "a state is named by a content digest"),
                () -> assertEquals(List.of(first.stateId()), repository.stateIds()));

        // Shorten the last clip on the video track, so the second state genuinely differs.
        Path project = workspace.resolve("sample.kdenlive");
        String edited = Files.readString(project, StandardCharsets.UTF_8)
                .replace("out=\"00:00:45.462\" producer=\"chain4\"",
                        "out=\"00:00:30.000\" producer=\"chain4\"");
        assertNotEquals(Files.readString(project, StandardCharsets.UTF_8), edited,
                "the edit must actually change the file");
        Files.writeString(project, edited, StandardCharsets.UTF_8);

        assertEquals(0, run(workspace, "commit", "-m", "Trim the last clip"), errors());
        Commit second = repository.head().orElseThrow();

        assertAll(
                () -> assertEquals(first.id(), second.parentId()),
                () -> assertNotEquals(first.stateId(), second.stateId(),
                        "a real edit produces a genuinely different state"),
                () -> assertNotEquals(first.id(), second.id()));

        // The two states must hold genuinely different edits, parsed not fabricated.
        ProjectState before = repository.state(first.stateId());
        ProjectState after = repository.state(second.stateId());
        assertAll(
                () -> assertEquals(2900L, before.project().timeline().duration()),
                () -> assertEquals(2529L, after.project().timeline().duration(),
                        "the last clip now ends 371 frames earlier, so the timeline is shorter"),
                () -> assertEquals(4, after.project().assetCount(),
                        "the same four media files are still referenced"),
                () -> assertNotEquals(before.project(), after.project()));

        out.reset();
        assertEquals(0, run(workspace, "log"), errors());
        String log = output();
        assertAll(
                () -> assertTrue(log.contains("Trim the last clip")),
                () -> assertTrue(log.contains("Initial edit")),
                () -> assertTrue(log.indexOf("Trim the last clip") < log.indexOf("Initial edit"),
                        "the newest commit must be shown first"),
                () -> assertTrue(log.contains(first.id().substring(0, 8))));

        out.reset();
        assertEquals(0, run(workspace, "checkout", first.id()), errors());
        assertAll(
                () -> assertTrue(output().contains(first.stateId())),
                () -> assertTrue(output().contains("2900"),
                        "checkout must report the state the commit stands for"),
                () -> assertEquals(2900L, repository.state(
                        repository.commit(first.id()).stateId()).project().timeline().duration()));
    }

    @Test
    void theWorkingProjectIsNotChangedByCheckout(@TempDir Path workspace) throws IOException {
        Path project = copySampleTo(workspace);
        assertEquals(0, run(workspace, "init"), errors());
        assertEquals(0, run(workspace, "commit", "-m", "Initial edit"), errors());

        String before = Files.readString(project, StandardCharsets.UTF_8);
        Repository repository = new FileSystemRepository(workspace);
        assertEquals(0, run(workspace, "checkout", repository.head().orElseThrow().id()), errors());

        assertEquals(before, Files.readString(project, StandardCharsets.UTF_8),
                "reading a state must never write to the project directory");
    }

    @Test
    void aFreshProcessSeesTheSameRepository(@TempDir Path workspace)
            throws IOException, InterruptedException {
        copySampleTo(workspace);
        assertEquals(0, run(workspace, "init"), errors());
        assertEquals(0, run(workspace, "commit", "-m", "Initial edit"), errors());
        String commitId = new FileSystemRepository(workspace).head().orElseThrow().id();

        // A separate JVM, so nothing can be carried over in memory.
        Process process = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", runtimeClasspath(),
                "com.vvcs.cli.VvcsCli", "log")
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        String forkedOutput = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "the forked process failed: " + forkedOutput);

        assertAll(
                () -> assertTrue(forkedOutput.contains("Initial edit"),
                        "a new process must find the history on disk: " + forkedOutput),
                () -> assertTrue(forkedOutput.contains(commitId.substring(0, 8))));
    }

    /**
     * The classpath a separate JVM needs, built from where the code actually lives. The
     * test runner's own classpath is not usable here, because it points at a launcher jar
     * rather than at the project.
     */
    private static String runtimeClasspath() {
        List<Path> entries = new java.util.LinkedList<>();
        entries.add(Path.of(VvcsCli.class.getProtectionDomain().getCodeSource()
                .getLocation().getPath()));
        for (Class<?> required : List.of(ObjectMapper.class, JavaTimeModule.class,
                JsonFactory.class, JsonIgnore.class)) {
            entries.add(Path.of(required.getProtectionDomain().getCodeSource()
                    .getLocation().getPath()));
        }
        return entries.stream().distinct().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    // -------------------------------------------------------------- refusals

    @Test
    void commitRefusesToRunBeforeInit(@TempDir Path workspace) throws IOException {
        copySampleTo(workspace);
        assertEquals(1, run(workspace, "commit", "-m", "Initial edit"));
        assertTrue(errors().contains("vvcs init"));
    }

    @Test
    void initRefusesToOverwriteAnExistingRepository(@TempDir Path workspace) {
        assertAll(
                () -> assertEquals(0, run(workspace, "init"), errors()),
                () -> assertEquals(1, run(workspace, "init")),
                () -> assertTrue(errors().contains("already exists")));
    }

    @Test
    void commitNeedsAMessage(@TempDir Path workspace) throws IOException {
        copySampleTo(workspace);
        run(workspace, "init");
        assertEquals(2, run(workspace, "commit"));
        assertTrue(errors().contains("needs a message"));
    }

    @Test
    void logOnAnEmptyRepositorySaysSoRatherThanFailing(@TempDir Path workspace) {
        run(workspace, "init");
        assertEquals(0, run(workspace, "log"));
        assertTrue(output().contains("no commits yet"));
    }

    @Test
    void anUnknownCommitIsReportedNotIgnored(@TempDir Path workspace) {
        run(workspace, "init");
        assertEquals(1, run(workspace, "checkout", "deadbeef"));
        assertTrue(errors().contains("no such commit"));
    }

    @Test
    void anUnknownCommandIsAUsageError(@TempDir Path workspace) {
        assertEquals(2, run(workspace, "frobnicate"));
        assertTrue(errors().contains("unknown command"));
    }

    @Test
    void commitExplainsItselfWhenNoProjectCanBeFound(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve(".vvcs"));
        new FileSystemRepository(workspace).initialize("Krishant");
        assertEquals(1, run(workspace, "commit", "-m", "Initial edit"));
        assertTrue(errors().contains("no .kdenlive project"));
    }

    @Test
    void commitRefusesToGuessBetweenSeveralProjects(@TempDir Path workspace) throws IOException {
        copySampleTo(workspace);
        Files.copy(SAMPLE, workspace.resolve("other.kdenlive"), StandardCopyOption.REPLACE_EXISTING);
        run(workspace, "init");
        assertEquals(1, run(workspace, "commit", "-m", "Initial edit"));
        assertTrue(errors().contains("more than one"));

        out.reset();
        err.reset();
        assertEquals(0, run(workspace, "commit", "-m", "Initial edit",
                "--project", "other.kdenlive"), errors());
    }

    @Test
    void anExplicitProjectIsCommittedEvenWithOthersPresent(@TempDir Path workspace)
            throws IOException {
        copySampleTo(workspace);
        Files.copy(SAMPLE, workspace.resolve("other.kdenlive"), StandardCopyOption.REPLACE_EXISTING);
        run(workspace, "init");
        assertEquals(0, run(workspace, "commit", "-m", "Initial edit",
                "--project", "other.kdenlive"), errors());
        Commit committed = new FileSystemRepository(workspace).head().orElseThrow();
        assertEquals(64, committed.stateId().length());
    }

    // ---------------------------------------------------------- the parser is real

    @Test
    void theCommittedStateIsWhatTheParserProduced(@TempDir Path workspace) throws IOException {
        Path copy = copySampleTo(workspace);
        run(workspace, "init");
        run(workspace, "commit", "-m", "Initial edit");

        var repository = new FileSystemRepository(workspace);
        var stored = repository.state(repository.head().orElseThrow().stateId()).project();
        var parsed = new KdenliveParser().parse(copy);

        assertAll(
                () -> assertEquals(parsed, stored),
                () -> assertEquals("sample", stored.name()),
                () -> assertEquals(4, stored.assetCount()),
                () -> assertEquals(4, stored.timeline().tracks().size()));
    }

    @Test
    void theSampleProjectItselfIsNeverModified(@TempDir Path workspace) throws IOException {
        String original = Files.readString(SAMPLE, StandardCharsets.UTF_8);
        copySampleTo(workspace);
        run(workspace, "init");
        run(workspace, "commit", "-m", "Initial edit");
        run(workspace, "commit", "-m", "Second edit");
        run(workspace, "log");

        assertEquals(original, Files.readString(SAMPLE, StandardCharsets.UTF_8),
                "the real sample project must not be touched by a test");
    }

    @Test
    void twoRepositoriesInDifferentDirectoriesStayIndependent(@TempDir Path root)
            throws IOException {
        Path first = root.resolve("one");
        Path second = root.resolve("two");
        copySampleTo(first);
        copySampleTo(second);
        run(first, "init");
        run(second, "init");
        run(first, "commit", "-m", "One");
        run(second, "commit", "-m", "Two");

        assertAll(
                () -> assertEquals("One",
                        new FileSystemRepository(first).head().orElseThrow().message()),
                () -> assertEquals("Two",
                        new FileSystemRepository(second).head().orElseThrow().message()));
    }

    @Test
    void timestampsAreIso8601InStoredJson(@TempDir Path workspace) throws IOException {
        copySampleTo(workspace);
        run(workspace, "init");
        run(workspace, "commit", "-m", "Initial edit");

        String commitJson = Files.readString(
                workspace.resolve(".vvcs/commits").resolve(
                        new FileSystemRepository(workspace).head().orElseThrow().id() + ".json"),
                StandardCharsets.UTF_8);

        assertTrue(commitJson.contains("\"timestamp\""), commitJson);
        assertTrue(commitJson.matches("(?s).*\"timestamp\"\\s*:\\s*\"\\d{4}-\\d{2}-\\d{2}T.*\".*"),
                "a timestamp must be human readable ISO-8601: " + commitJson);
        assertTrue(commitJson.contains("\n  "), "stored commits must be readable");
    }

    @Test
    void aFixedClockMakesCommitIdentitiesReproducible(@TempDir Path workspace) throws IOException {
        copySampleTo(workspace);
        Path other = workspace.resolve("other");
        Files.createDirectories(other);
        Files.copy(SAMPLE, other.resolve("sample.kdenlive"));

        Clock fixed = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        for (Path target : List.of(workspace, other)) {
            PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
            PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8);
            assertEquals(0, new VvcsCli(target, fixed, stdout, stderr).run(
                    new String[]{"init"}), errors());
            assertEquals(0, new VvcsCli(target, fixed, stdout, stderr).run(
                    new String[]{"commit", "-m", "Initial edit"}), errors());
        }

        assertEquals(new FileSystemRepository(workspace).head().orElseThrow().id(),
                new FileSystemRepository(other).head().orElseThrow().id(),
                "identical content, author and time must produce the identical commit id");
        assertFalse(Files.exists(workspace.resolve(".vvcs/HEAD.tmp")),
                "no temporary file may be left behind");
    }
}
