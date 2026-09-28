package com.vvcs.web;

import com.sun.net.httpserver.HttpExchange;
import com.vvcs.comparison.TimelineComparison;
import com.vvcs.comparison.TimelineComparisonService;
import com.vvcs.repository.Repository;
import com.vvcs.repository.RepositoryException;
import com.vvcs.serialization.VvcsJson;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one read-only endpoint: {@code GET /api/diff/{commitA}/{commitB}}.
 *
 * <p>This class is an adapter and nothing else. It resolves nothing, compares nothing and
 * formats nothing for a user: it takes two commit ids, hands them to
 * {@link TimelineComparisonService}, and serializes what comes back. The diff itself is
 * still the core's, so the browser and the command line are looking at the same answer to
 * the same question.
 *
 * <p>Nothing is cached and nothing is mutable, which is why there is no state here beyond
 * the repository: two requests for the same pair produce the same bytes.
 */
public final class DiffApi {

    private final Repository repository;
    private final TimelineComparisonService service;

    public DiffApi(Repository repository) {
        this(repository, new TimelineComparisonService(repository));
    }

    /**
     * @param service the shared comparison service, injectable so that a test can serve a
     *                comparison without a repository behind it
     */
    public DiffApi(Repository repository, TimelineComparisonService service) {
        this.repository = repository;
        this.service = service;
    }

    /**
     * Handles a request whose path has already been reduced to
     * {@code [commitA, commitB]}.
     *
     * @return the HTTP status to send
     */
    public int handle(HttpExchange exchange, String[] commitIds) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return writeError(exchange, 405, "Method not allowed",
                    "This endpoint only answers GET.");
        }
        if (commitIds.length != 2) {
            return writeError(exchange, 400, "Two commits required",
                    "Use /api/diff/{commitA}/{commitB}.");
        }
        if (!repository.isInitialized()) {
            // A server pointed at the wrong directory, which is an operator problem rather
            // than a bad request, and one the reader can act on.
            return writeError(exchange, 503, "No repository here",
                    "The server is not pointed at a VVCS repository. Start it in one, or pass"
                            + " --repo.");
        }

        TimelineComparison comparison;
        try {
            comparison = service.compare(commitIds[0], commitIds[1]);
        } catch (RepositoryException e) {
            // The commit is not in this repository, or never was. The message comes from the
            // repository, which is the only thing that knows which of the two it was.
            return writeError(exchange, 404, "Commit not found", e.getMessage());
        }

        writeJson(exchange, 200, VvcsJson.toJson(comparison));
        return 200;
    }

    static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // A comparison is content addressed by its two commits, so a browser or proxy may
        // keep it, but only if the same request produced the same bytes.
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Answers a failure in the same shape as a success, so the page has one thing to read.
     * The status carries the meaning; the body carries something a person can show.
     */
    static int writeError(HttpExchange exchange, int status, String title, String detail)
            throws IOException {
        Map<String, Object> body = Map.of(
                "error", Map.of(
                        "status", status,
                        "title", title,
                        "detail", detail == null ? "" : detail));
        writeJson(exchange, status, VvcsJson.toJson(body));
        return status;
    }

    /** Rethrown so a handler can be written without a try block around serialization. */
    static UncheckedIOException wrap(IOException e) {
        return new UncheckedIOException(e);
    }
}
