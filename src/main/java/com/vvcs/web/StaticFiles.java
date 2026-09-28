package com.vvcs.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the frontend's own files, and nothing else.
 *
 * <p>Two rules shape this. Only files under one directory are ever readable, so a request
 * cannot walk out of it with {@code ..}. And any path that is not a file is answered with
 * the single page, so that a comparison at {@code /diff/a/b} survives a reload or a shared
 * link instead of 404ing on the server that has no such route.
 */
public final class StaticFiles {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "woff2", "font/woff2");

    private final Path root;
    private final Path index;

    public StaticFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.index = this.root.resolve("index.html");
    }

    /**
     * Answers a request for a frontend file.
     *
     * @param requestPath the path, already split and still percent encoded
     * @return the HTTP status to send
     */
    public int handle(HttpExchange exchange, String requestPath) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return DiffApi.writeError(exchange, 405, "Method not allowed",
                    "This endpoint only answers GET.");
        }
        Path file = resolve(requestPath);
        if (file == null) {
            // The path tried to leave the root. Said plainly, because a 403 with no
            // explanation reads like a broken server.
            return DiffApi.writeError(exchange, 403, "Refused", "That path is outside the app.");
        }
        if (Files.isDirectory(file)) {
            file = file.resolve("index.html");
        }
        if (!Files.isRegularFile(file)) {
            // Not a file, so it is a route the single page owns.
            file = index;
        }
        if (!Files.isRegularFile(file)) {
            return DiffApi.writeError(exchange, 404, "Not found",
                    "No such file, and no index.html to fall back on.");
        }

        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        // The page is a development prototype being edited alongside the server; a stale
        // cached script is worse than a request.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        return 200;
    }

    /**
     * Maps a request path to a file inside the root, or null if it would escape.
     *
     * <p>Decoding happens first and the result is normalized afterwards, so a path that
     * decodes to {@code ../} is caught by the containment check rather than by a guess about
     * which encodings to expect. The leading slash is dropped because a request path is
     * absolute, and resolving one against the root would escape it every time.
     */
    Path resolve(String requestPath) {
        String decoded = URLDecoder.decode(requestPath, StandardCharsets.UTF_8);
        String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        Path candidate = root.resolve(relative).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
