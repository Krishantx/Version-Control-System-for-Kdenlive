package com.vvcs.cli;

import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument handling and author defaults, without needing a project to be present.
 */
class VvcsCliTest {

    private static final Path SAMPLE = Paths.get("sample-project", "sample.kdenlive");

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @TempDir
    Path workspace;

    private Path withProject;

    @BeforeEach
    void copySample() throws IOException {
        withProject = workspace.resolve("sample.kdenlive");
        Files.copy(SAMPLE, withProject, StandardCopyOption.REPLACE_EXISTING);
    }

    private int run(String... args) {
        return new VvcsCli(workspace, Clock.systemDefaultZone(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String storedAuthor() throws IOException {
        return Files.readString(workspace.resolve(".vvcs/repository.json"), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------- init

    @Test
    void initAcceptsAnAuthorAsASeparateValue() throws IOException {
        assertEquals(0, run("init", "--author", "Ana"), errors());
        assertAll(
                () -> assertTrue(storedAuthor().contains("Ana"), storedAuthor()),
                () -> assertTrue(output().contains("default author: Ana")));
    }

    @Test
    void initAcceptsAnAuthorWithAnEqualsSign() throws IOException {
        assertEquals(0, run("init", "--author=Ana"), errors());
        assertTrue(storedAuthor().contains("Ana"), storedAuthor());
    }

    @Test
    void initWithoutAnAuthorFallsBackToTheSystemUser() throws IOException {
        assertEquals(0, run("init"), errors());
        String expected = System.getProperty("user.name");
        assertAll(
                () -> assertTrue(storedAuthor().contains(expected), storedAuthor()),
                () -> assertTrue(output().contains("default author: " + expected)));
    }

    @Test
    void initRejectsAnAuthorWithNoValue() {
        assertEquals(2, run("init", "--author"));
        assertTrue(errors().contains("--author needs a value"), errors());
    }

    @Test
    void initRejectsAnUnrelatedOption() {
        assertEquals(2, run("init", "--project", "other.kdenlive"), errors());
        assertTrue(errors().contains("init takes no"), errors());
    }

    // ----------------------------------------------------------------- commit

    @Test
    void commitAcceptsItsMessageEitherWay() {
        run("init");
        assertAll(
                () -> assertEquals(0, run("commit", "-m", "One"), errors()),
                () -> assertEquals(0, run("commit", "--message", "Two"), errors()),
                () -> assertEquals(0, run("commit", "--message=Three"), errors()));
    }

    @Test
    void commitTakesItsAuthorEitherWay() {
        run("init");
        assertEquals(0, run("commit", "-m", "One", "--author", "Ana"), errors());
        assertEquals(0, run("commit", "-m", "Two", "--author=Bo"), errors());

        String log = runAndCapture("log");
        assertAll(
                () -> assertTrue(log.contains("Author: Ana"), log),
                () -> assertTrue(log.contains("Author: Bo"), log));
    }

    @Test
    void commitWithoutAnAuthorUsesTheRepositoryDefault() {
        run("init", "--author", "Repo Owner");
        assertEquals(0, run("commit", "-m", "One"), errors());
        assertTrue(runAndCapture("log").contains("Author: Repo Owner"));
    }

    @Test
    void commitRejectsAMessageFlagWithNoValue() {
        run("init");
        assertEquals(2, run("commit", "-m"));
        assertTrue(errors().contains("-m needs a value"), errors());
    }

    @Test
    void commitRejectsAMessageThatIsOnlyWhitespace() {
        run("init");
        assertEquals(2, run("commit", "-m", "   "));
        assertTrue(errors().contains("needs a message"));
    }

    @Test
    void commitRejectsAnOptionItDoesNotKnow() {
        run("init");
        assertEquals(2, run("commit", "-m", "One", "--amend"));
        assertTrue(errors().contains("takes no option: --amend"), errors());
    }

    // ---------------------------------------------------------------- checkout

    @Test
    void checkoutNeedsExactlyOneCommit() {
        run("init");
        assertAll(
                () -> assertEquals(2, run("checkout"), errors()),
                () -> assertTrue(errors().contains("exactly one"), errors()),
                () -> assertEquals(2, run("checkout", "aa", "bb"), errors()));
    }

    // --------------------------------------------------------------------- log

    @Test
    void logRejectsArguments() {
        run("init");
        assertAll(
                () -> assertEquals(2, run("log", "extra"), errors()),
                () -> assertTrue(errors().contains("log takes no argument"), errors()),
                () -> assertEquals(2, run("log", "--verbose"), errors()),
                () -> assertTrue(errors().contains("log takes no option"), errors()));
    }

    // ----------------------------------------------------------------- general

    @Test
    void noArgumentsPrintsUsage() {
        assertEquals(2, run());
        assertTrue(errors().contains("usage: vvcs"));
    }

    @Test
    void helpSucceedsAndGoesToStandardOutput() {
        assertAll(
                () -> assertEquals(0, run("help"), errors()),
                () -> assertEquals(0, run("--help"), errors()),
                () -> assertEquals(0, run("-h"), errors()));
        assertTrue(output().contains("usage: vvcs"));
        assertTrue(errors().isEmpty(), "help is not an error");
    }

    @Test
    void usageMentionsEveryCommand() {
        run("help");
        String usage = output();
        for (String command : new String[]{"init", "commit", "log", "checkout"}) {
            assertTrue(usage.contains(command), "usage does not mention " + command);
        }
    }

    @Test
    void anEmptyMessageIsNotAcceptedEvenWhenFlagged() {
        run("init");
        assertEquals(2, run("commit", "--message", ""));
        assertTrue(errors().contains("needs a message"));
    }

    private String runAndCapture(String... args) {
        out.reset();
        run(args);
        return output();
    }
}
