package com.vvcs.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.vvcs.comparison.TimelineComparison;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.SourceRange;
import com.vvcs.model.TimeRange;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;
import com.vvcs.repository.TestProjects;
import com.vvcs.serialization.VvcsJson;
import com.vvcs.workflow.CommitWorkflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web application, over real HTTP.
 *
 * <p>A server is started on an operating-system-assigned port and driven with the JDK's own
 * client, so these tests exercise routing, status codes, headers and bodies exactly as a
 * browser would. A test that only called the handler methods in process would pass whether or
 * not the paths were wired up, and the paths are most of what there is to get wrong here.
 *
 * <p>The projects are the real sample, parsed from
 * {@code sample-project/sample.kdenlive}, because the point of the API is to serve what the
 * rest of the system actually produces, and a hand-built project with three fields would not
 * notice a field the viewer needs.
 */
class VvcsWebServerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"),
            ZoneOffset.UTC);
    private static final String AUTHOR = "editor@example.com";

    /**
     * One directory for the whole class, rather than a fresh {@code @TempDir} per test.
     *
     * <p>JUnit hands each test method its own temporary directory, so a per-method one would
     * give the server a different repository than the test committed into, and every request
     * would have been answered with "no repository here" for reasons that had nothing to do
     * with the server.
     */
    @TempDir
    static Path shared;

    private VvcsWebServer server;
    private Path work;

    @BeforeEach
    void startServer() throws IOException {
        work = Files.createTempDirectory(shared, "case-");
        // A web root with only the page in it: the routing tests do not need a real frontend,
        // and the file tests use the real one.
        Path webRoot = work.resolve("web");
        Files.createDirectories(webRoot);
        Files.writeString(webRoot.resolve("index.html"), "<!doctype html><title>vvcs</title>");

        server = VvcsWebServer.start(new FileSystemRepository(work, CLOCK), webRoot, 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Nested
    @DisplayName("GET /api/diff/{a}/{b}")
    class DiffEndpoint {

        @Test
        @DisplayName("returns the comparison the service builds, for two real commits")
        void returnsComparison() throws IOException {
            String[] pair = twoRealCommits(work);

            Response response = get("/api/diff/" + pair[0] + "/" + pair[1]);

            assertEquals(200, response.status());
            assertTrue(response.contentType().startsWith("application/json"), response.contentType());

            TimelineComparison comparison =
                    VvcsJson.read(response.body(), TimelineComparison.class);
            assertAll(
                    () -> assertNotNull(comparison.from()),
                    () -> assertNotNull(comparison.to()),
                    () -> assertNotNull(comparison.before()),
                    () -> assertNotNull(comparison.after()),
                    () -> assertNotNull(comparison.changes()),
                    () -> assertEquals(pair[0], comparison.from().shortId()),
                    () -> assertEquals(pair[1], comparison.to().shortId()));
        }

        @Test
        @DisplayName("the body is the same bytes the command line's --json produces")
        void sameBytesAsTheCli() throws IOException {
            String[] pair = twoRealCommits(work);

            Response viaHttp = get("/api/diff/" + pair[0] + "/" + pair[1]);

            // The endpoint adds nothing: it hands the service's comparison to the serializer,
            // so a browser and a terminal are looking at one answer, not two.
            var repository = new FileSystemRepository(work, CLOCK);
            String direct = VvcsJson.toJson(new com.vvcs.comparison.TimelineComparisonService(repository)
                    .compare(pair[0], pair[1]));
            assertEquals(direct, viaHttp.body());
        }

        @Test
        @DisplayName("both timelines carry the tracks, clips and frames the viewer draws")
        void carriesEverythingTheViewerNeeds() throws IOException {
            String[] pair = twoRealCommits(work);

            JsonNode body = VvcsJson.getObjectMapper()
                    .readTree(get("/api/diff/" + pair[0] + "/" + pair[1]).body());

            JsonNode before = body.get("before");
            assertAll(
                    () -> assertTrue(before.get("tracks").size() >= 3, "the sample has several tracks"),
                    () -> assertTrue(before.get("duration").asLong() > 0),
                    () -> assertNotNull(before.get("frameRate")),
                    () -> assertNotNull(before.get("resolution")),
                    () -> assertNotNull(before.get("projectName")));

            JsonNode firstClip = before.get("tracks").get(0).get("clips").get(0);
            assertAll(
                    () -> assertTrue(firstClip.has("start"), "a clip needs a start to be placed"),
                    () -> assertTrue(firstClip.has("duration"), "and a duration to be sized by"),
                    () -> assertTrue(firstClip.has("assetName"), "and something to call itself"),
                    () -> assertTrue(firstClip.has("trackId")),
                    () -> assertTrue(firstClip.has("sourceIn")),
                    () -> assertTrue(firstClip.has("sourceOut")),
                    () -> assertTrue(firstClip.has("sourceFrameRate")));
        }

        @Test
        @DisplayName("the changes are the engine's, with both sides of each")
        void changesCarryBothSides() throws IOException {
            String[] pair = twoRealCommits(work);

            JsonNode changes = VvcsJson.getObjectMapper()
                    .readTree(get("/api/diff/" + pair[0] + "/" + pair[1]).body())
                    .get("changes");

            assertTrue(changes.get("clipChanges").size() >= 1, "the two commits really do differ");
            JsonNode clipChange = changes.get("clipChanges").get(0);
            assertAll(
                    () -> assertNotNull(clipChange.get("type")),
                    () -> assertNotNull(clipChange.get("kinds")),
                    () -> assertNotNull(clipChange.get("before")),
                    () -> assertNotNull(clipChange.get("after")),
                    () -> assertNotNull(clipChange.get("before").get("trackId")),
                    () -> assertNotNull(clipChange.get("after").get("timelineStart")));
        }

        @Test
        @DisplayName("a comparison with no changes is still a 200, with both timelines")
        void emptyDiff() throws IOException {
            String[] pair = twoRealCommits(work);
            // A third commit of the *same* project, so there is genuinely nothing to report.
            // Committing the file as it stands on disk would not do: that is the untrimmed
            // original, and the comparison would be anything but empty.
            var repository = new FileSystemRepository(work, CLOCK);
            new CommitWorkflow(repository, CLOCK)
                    .commit(trimmedFirstClip(parse(work)), "same again", AUTHOR);
            String head = repository.head().orElseThrow().shortId();

            Response response = get("/api/diff/" + pair[1] + "/" + head);

            assertEquals(200, response.status());
            TimelineComparison comparison =
                    VvcsJson.read(response.body(), TimelineComparison.class);
            assertAll(
                    () -> assertTrue(comparison.isEmpty()),
                    () -> assertEquals(0, comparison.changes().clipChanges().size()),
                    // The viewer still needs both sides to draw, and they are there
                    () -> assertNotNull(comparison.before()),
                    () -> assertNotNull(comparison.after()),
                    () -> assertEquals(comparison.before().trackCount(),
                            comparison.after().trackCount()),
                    () -> assertTrue(comparison.before().clipCount() > 0));
        }

        @Test
        @DisplayName("a short id is accepted, as a reader would type it")
        void shortIdsAreAccepted() throws IOException {
            String[] pair = twoRealCommits(work);

            TimelineComparison shortForm = VvcsJson.read(
                    get("/api/diff/" + pair[0] + "/" + pair[1]).body(), TimelineComparison.class);
            String fullA = new FileSystemRepository(work, CLOCK).commit(pair[0]).id();
            String fullB = new FileSystemRepository(work, CLOCK).commit(pair[1]).id();
            TimelineComparison longForm = VvcsJson.read(
                    get("/api/diff/" + fullA + "/" + fullB).body(), TimelineComparison.class);

            assertEquals(longForm, shortForm);
        }

        @Test
        @DisplayName("the comparison is the same every time it is asked for")
        void isDeterministic() throws IOException {
            String[] pair = twoRealCommits(work);

            String first = get("/api/diff/" + pair[0] + "/" + pair[1]).body();
            for (int i = 0; i < 5; i++) {
                assertEquals(first, get("/api/diff/" + pair[0] + "/" + pair[1]).body(),
                        "a browser may cache this, so two identical requests must agree");
            }
        }

        @Test
        @DisplayName("comparing a commit with itself is allowed and empty")
        void commitWithItself() throws IOException {
            String[] pair = twoRealCommits(work);

            Response response = get("/api/diff/" + pair[0] + "/" + pair[0]);

            assertEquals(200, response.status());
            assertTrue(VvcsJson.read(response.body(), TimelineComparison.class).isEmpty());
        }
    }

    @Nested
    @DisplayName("refusals")
    class Failures {

        @Test
        @DisplayName("an unknown commit is a 404, saying which")
        void unknownCommit() throws IOException {
            String[] pair = twoRealCommits(work);

            Response response = get("/api/diff/deadbeef/" + pair[1]);

            assertEquals(404, response.status());
            JsonNode error = VvcsJson.getObjectMapper().readTree(response.body()).get("error");
            assertAll(
                    () -> assertEquals(404, error.get("status").asInt()),
                    () -> assertEquals("Commit not found", error.get("title").asText()),
                    () -> assertTrue(error.get("detail").asText().contains("deadbeef"),
                            "the message names what was asked for: " + error.get("detail").asText()));
        }

        @Test
        @DisplayName("both commits are checked")
        void bothCommitsChecked() throws IOException {
            twoRealCommits(work);
            assertEquals(404, get("/api/diff/deadbeef/deadbeef").status());
        }

        @Test
        @DisplayName("one commit is a 400, with the shape of the request")
        void oneCommit() throws IOException {
            String[] pair = twoRealCommits(work);

            Response response = get("/api/diff/" + pair[0]);

            assertEquals(400, response.status());
            assertTrue(response.body().contains("/api/diff/{commitA}/{commitB}"),
                    "the error says what was wanted: " + response.body());
        }

        @Test
        @DisplayName("an unknown endpoint is a 404, not a stray index page")
        void unknownEndpoint() {
            Response response = get("/api/nonsense");

            assertEquals(404, response.status());
            assertTrue(response.body().contains("No such endpoint"), response.body());
        }

        @Test
        @DisplayName("anything but GET is refused")
        void otherMethods() throws IOException {
            String[] pair = twoRealCommits(work);

            assertEquals(405, request("POST", "/api/diff/" + pair[0] + "/" + pair[1]).status());
            assertEquals(405, request("DELETE", "/api/diff/" + pair[0] + "/" + pair[1]).status());
        }

        @Test
        @DisplayName("a directory with no repository says so, rather than 404ing the commits")
        void noRepository() throws IOException {
            Path empty = Files.createDirectory(work.resolve("empty"));
            try (VvcsWebServer other = VvcsWebServer.start(
                    new FileSystemRepository(empty, CLOCK), work.resolve("web"), 0)) {
                Response response = requestAt(other, "GET", "/api/diff/aaaa/bbbb");

                assertEquals(503, response.status());
                assertTrue(response.body().contains("No repository here"), response.body());
            }
        }
    }

    @Nested
    @DisplayName("GET /api/commits")
    class Commits {

        @Test
        @DisplayName("lists the history, newest first, with the ids a reader needs")
        void listsHistory() throws IOException {
            twoRealCommits(work);

            JsonNode commits = VvcsJson.getObjectMapper()
                    .readTree(get("/api/commits").body());

            assertEquals(2, commits.size());
            assertAll(
                    () -> assertNotNull(commits.get(0).get("shortId")),
                    () -> assertNotNull(commits.get(0).get("message")),
                    () -> assertNotNull(commits.get(0).get("author")),
                    // Newest first, which is the order the history command uses
                    () -> assertEquals("second cut", commits.get(0).get("message").asText()));
        }

        @Test
        @DisplayName("an empty repository is an empty list, not an error")
        void emptyRepository() throws IOException {
            Path empty = Files.createDirectory(work.resolve("empty"));
            new FileSystemRepository(empty, CLOCK).initialize(AUTHOR);
            try (VvcsWebServer other = VvcsWebServer.start(
                    new FileSystemRepository(empty, CLOCK), work.resolve("web"), 0)) {
                Response response = requestAt(other, "GET", "/api/commits");

                assertEquals(200, response.status());
                assertEquals("[]", response.body().replaceAll("\\s+", ""),
                        "an empty history is an empty array, not a failure");
            }
        }
    }

    @Nested
    @DisplayName("the single page")
    class Page {

        @Test
        @DisplayName("is served for the root, so the viewer has somewhere to start")
        void rootServesThePage() {
            Response response = get("/");

            assertEquals(200, response.status());
            assertTrue(response.body().contains("<title>vvcs</title>"), response.body());
            assertTrue(response.contentType().startsWith("text/html"), response.contentType());
        }

        @Test
        @DisplayName("is served for a comparison path, so a link can be shared and reloaded")
        void comparisonPathServesThePage() throws IOException {
            String[] pair = twoRealCommits(work);

            // The server has no route for /diff/a/b, which is the point: the page owns it, and
            // a reload has to land on the same place rather than a 404.
            Response response = get("/diff/" + pair[0] + "/" + pair[1]);

            assertEquals(200, response.status());
            assertTrue(response.body().contains("<title>vvcs</title>"), response.body());
        }

        @Test
        @DisplayName("a file request is served as itself, with the right type")
        void filesAreServed() throws IOException {
            Path root = Path.of("web").toAbsolutePath();

            Response js = requestAtFile(root, "/lib/layout.js");
            Response css = requestAtFile(root, "/styles.css");

            assertAll(
                    () -> assertEquals(200, js.status()),
                    () -> assertTrue(js.contentType().startsWith("text/javascript"), js.contentType()),
                    () -> assertEquals(200, css.status()),
                    () -> assertTrue(css.contentType().startsWith("text/css"), css.contentType()));
        }

        @Test
        @DisplayName("a path that climbs out of the app is refused")
        void traversalIsRefused() {
            Response response = get("/../pom.xml");

            // Either refused outright, or normalised back to the page. What must not happen is
            // a file from outside the app being served.
            assertTrue(response.status() == 403 || response.status() == 200,
                    "traversal must not read outside the app, got " + response.status());
            assertFalse(response.body().contains("<project"),
                    "a build file was served to the browser");
        }
    }

    // -------------------------------------------------------------------------------------
    // Two real commits over the real sample project, so the API is tested against what the
    // rest of the system actually produces.
    // -------------------------------------------------------------------------------------

    private String[] twoRealCommits(Path work) throws IOException {
        Files.writeString(work.resolve("sample.kdenlive"),
                Files.readString(TestProjects.SAMPLE));
        Repository repository = new FileSystemRepository(work, CLOCK);
        repository.initialize(AUTHOR);
        CommitWorkflow workflow = new CommitWorkflow(repository, CLOCK);

        workflow.commit(parse(work), "first cut", AUTHOR);
        workflow.commit(trimmedFirstClip(parse(work)), "second cut", AUTHOR);

        List<String> ids = new ArrayList<>();
        for (var commit : repository.history(repository.head().orElseThrow().id())) {
            ids.add(commit.shortId());
        }
        return new String[]{ids.get(1), ids.get(0)};
    }

    private Project parse(Path work) throws IOException {
        return new com.vvcs.parser.KdenliveParser().parse(work.resolve("sample.kdenlive"));
    }

    /** Shortens the sample's first standalone video clip, leaving the file alone. */
    private Project trimmedFirstClip(Project project) {
        List<Track> tracks = new ArrayList<>();
        for (Track track : project.timeline().tracks()) {
            List<Clip> clips = new ArrayList<>();
            boolean trimmed = false;
            for (Clip clip : track.clips()) {
                boolean pairHalf = project.timeline().tracks().stream()
                        .flatMap(t -> t.clips().stream())
                        .anyMatch(other -> !other.id().equals(clip.id())
                                && other.sourceId().equals(clip.sourceId()));
                if (!trimmed && track.type() == TrackType.VIDEO && !pairHalf) {
                    trimmed = true;
                    long duration = 100;
                    clips.add(new Clip(clip.id(), clip.assetId(), track.id(),
                            new SourceRange(clip.source().inFrame(),
                                    clip.source().inFrame() + duration - 1, duration,
                                    clip.source().timebase()),
                            new TimeRange(clip.timeline().start(), duration), clip.sourceId()));
                } else {
                    clips.add(clip);
                }
            }
            tracks.add(new Track(track.id(), track.type(), clips));
        }
        return new Project(project.name(), project.resolution(), project.frameRate(),
                project.assets(),
                new com.vvcs.model.Timeline(project.timeline().id(), project.timeline().name(),
                        project.frameRate(), project.resolution(),
                        project.timeline().duration(), tracks));
    }

    // -------------------------------------------------------------------------------------

    private Response get(String path) {
        return request("GET", path);
    }

    private Response request(String method, String path) {
        return requestAt(server, method, path);
    }

    private static Response requestAt(VvcsWebServer target, String method, String path) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI
                    .create(target.baseUrl() + path).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            int status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, connection.getHeaderField("Content-Type"), body);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A one-off server over the real frontend directory. */
    private static Response requestAtFile(Path webRoot, String path) throws IOException {
        try (VvcsWebServer target = VvcsWebServer.start(
                new FileSystemRepository(webRoot, CLOCK), webRoot, 0)) {
            return requestAt(target, "GET", path);
        }
    }

    private record Response(int status, String contentType, String body) {
    }
}
