package com.vvcs.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * The web application: one API route, and the frontend's files.
 *
 * <p>It is built on the HTTP server in the JDK on purpose. The project already depends on
 * exactly one library, and a read-only JSON endpoint for a local prototype does not justify
 * a framework, a servlet container, or a second language's package manager on top of that.
 *
 * <pre>
 *   GET /api/diff/{commitA}/{commitB}   a comparison, as JSON
 *   GET /api/commits                    the history, for picking two commits
 *   GET /anything-else                  the single page
 * </pre>
 *
 * <p>Binding to port 0 asks the operating system for a free port, and {@link #port()} then
 * reports which one it was given. That is what lets the tests start a real server without
 * caring what else is running.
 */
public final class VvcsWebServer implements AutoCloseable {

    /** Where the frontend lives, relative to where the server was started. */
    public static final String DEFAULT_WEB_ROOT = "web";

    private final HttpServer server;
    private final Repository repository;
    private final int port;

    private VvcsWebServer(HttpServer server, Repository repository) {
        this.server = server;
        this.repository = repository;
        this.port = server.getAddress().getPort();
    }

    /** Starts a server for the repository in the current directory, on the given port. */
    public static VvcsWebServer start(Path repositoryRoot, Path webRoot, int port)
            throws IOException {
        return start(new FileSystemRepository(repositoryRoot), webRoot, port);
    }

    public static VvcsWebServer start(Repository repository, Path webRoot, int port)
            throws IOException {
        return start(repository, webRoot, port, HttpServer::create);
    }

    /**
     * The one seam through which this class touches the outside world.
     *
     * <p>{@code create} is {@link HttpServer#create(InetSocketAddress, int)} in production and
     * a recorder in the routing tests, which are about which request goes where and have no
     * business opening a port to find out.
     */
    interface Connector {
        HttpServer create(InetSocketAddress address, int backlog) throws IOException;
    }

    static VvcsWebServer start(Repository repository, Path webRoot, int port, Connector connector)
            throws IOException {
        HttpServer server = connector.create(new InetSocketAddress(port), 0);
        VvcsWebServer app = new VvcsWebServer(server, repository);

        DiffApi api = new DiffApi(repository);
        StaticFiles files = new StaticFiles(webRoot);
        HistoryApi history = new HistoryApi(repository);

        server.createContext("/api/", app.safe(exchange -> app.routeApi(exchange, api, history)));
        // Everything else is the single page, so that /diff/a/b is a real URL and a reload
        // of it works, rather than a hash the router has to be told about.
        server.createContext("/", app.safe(exchange -> files.handle(
                exchange, exchange.getRequestURI().getPath())));
        // A viewer is worth little at a thousand requests a second, and threads here would be
        // idle almost all of the time.
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return app;
    }

    private void routeApi(HttpExchange exchange, DiffApi api, HistoryApi history)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] segments = path.split("/");

        // /api/diff/{a}/{b} is the comparison. /api/commits is the history, so that the page
        // can offer two real commits to choose from instead of asking for ids blind.
        if ("diff".equals(segments[2])) {
            // A path that starts like a comparison but is short of one is a mistake in the
            // request rather than a missing page, and saying so is more use than a 404.
            if (segments.length == 5) {
                api.handle(exchange, new String[]{segments[3], segments[4]});
            } else {
                DiffApi.writeError(exchange, 400, "Two commits required",
                        "Use /api/diff/{commitA}/{commitB}.");
            }
        } else if (segments.length == 3 && "commits".equals(segments[2])) {
            history.handle(exchange);
        } else {
            DiffApi.writeError(exchange, 404, "No such endpoint", "Nothing answers " + path);
        }
    }

    /**
     * Makes sure a failure inside a handler becomes a response, not a dropped connection.
     * A browser left waiting on a request that will never be answered shows nothing at all,
     * which is a worse failure than a 500 it can report.
     */
    private HttpHandler safe(ThrowingRoute route) {
        return exchange -> {
            try {
                route.handle(exchange);
            } catch (Exception e) {
                if (exchange.getResponseCode() == -1) {
                    byte[] body = ("vvcs: " + e).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(500, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } else {
                    exchange.close();
                }
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRoute {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** The port actually bound, which is the only reliable answer when 0 was requested. */
    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public Repository repository() {
        return repository;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
