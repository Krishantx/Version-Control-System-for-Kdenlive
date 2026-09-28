package com.vvcs.web;

import com.vvcs.cli.VvcsCli;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code web} command's own behaviour: what it accepts, and what it refuses.
 *
 * <p>The server it starts is exercised in {@link VvcsWebServerTest}. This is about the front
 * door, which is where a mistyped option is caught and where a missing frontend directory has
 * to be reported before a port is opened.
 */
class WebCliTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"),
            ZoneOffset.UTC);

    @Test
    @DisplayName("the vvcs usage text lists it")
    void isDocumentedInTheVvcsUsage() {
        // Routing itself is MainRoutingTest's business; what matters here is that the command
        // is advertised, since `web` is not one anybody would guess.
        Result result = cli("help");

        assertTrue(result.out().contains("web"), result.out());
    }

    @Test
    @DisplayName("a missing frontend directory is reported before anything is opened")
    void missingFrontend(@TempDir Path work) {
        Result result = run("--web", work.resolve("nowhere").toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("no frontend at"), result.err());
        assertTrue(result.err().contains("--web"), "and says how to point at one: " + result.err());
    }

    @Test
    @DisplayName("a missing repository directory is reported")
    void missingRepository(@TempDir Path work) throws Exception {
        Path web = work.resolve("web");
        Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "<!doctype html>");

        Result result = run("--repo", work.resolve("nowhere").toString(), "--web", web.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("no such directory"), result.err());
    }

    @Test
    @DisplayName("an unknown option is refused by name")
    void unknownOption() {
        Result result = run("--nonsense");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--nonsense"), result.err());
    }

    @Test
    @DisplayName("an option that needs a value says so")
    void missingValue() {
        for (String option : new String[]{"--repo", "--port", "--web"}) {
            Result result = run(option);

            assertEquals(2, result.exitCode(), option);
            assertTrue(result.err().contains(option + " needs"), result.err());
        }
    }

    @Test
    @DisplayName("a port that is not a number is refused")
    void badPort() {
        Result result = run("--port", "http");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("--port needs a number"), result.err());
    }

    @Test
    @DisplayName("help is answered without touching the filesystem")
    void help() {
        Result result = run("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("usage: vvcs web"), result.out());
    }

    // -------------------------------------------------------------------------------------

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new WebCli(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private Result cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new VvcsCli(Path.of("."), CLOCK,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String out, String err) {
    }
}
