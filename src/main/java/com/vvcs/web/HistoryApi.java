package com.vvcs.web;

import com.sun.net.httpserver.HttpExchange;
import com.vvcs.comparison.CommitRef;
import com.vvcs.model.Commit;
import com.vvcs.repository.Repository;
import com.vvcs.repository.RepositoryException;
import com.vvcs.serialization.VvcsJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/commits}: the history, newest first, with just enough about each commit for
 * a reader to choose two of them.
 *
 * <p>This exists so the page can ask "which two commits?" rather than making someone go and
 * read {@code vvcs log} elsewhere. It reads commits and no states, so listing a long history
 * costs no parsing.
 *
 * <p>It is also the only reason a reader might expect more history features from this server,
 * so it deliberately answers only this: no diffs of arbitrary pairs, no branches, no
 * comparison of anything else.
 */
public final class HistoryApi {

    private final Repository repository;

    public HistoryApi(Repository repository) {
        this.repository = repository;
    }

    int handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return DiffApi.writeError(exchange, 405, "Method not allowed",
                    "This endpoint only answers GET.");
        }
        if (!repository.isInitialized()) {
            return DiffApi.writeError(exchange, 503, "No repository here",
                    "The server is not pointed at a VVCS repository. Start it in one, or pass"
                            + " --repo.");
        }

        List<CommitRef> commits;
        try {
            commits = historyOf(repository);
        } catch (RepositoryException e) {
            return DiffApi.writeError(exchange, 404, "No history", e.getMessage());
        }
        DiffApi.writeJson(exchange, 200, VvcsJson.toJson(commits));
        return 200;
    }

    /**
     * The history from HEAD, or every commit when there is no HEAD yet.
     *
     * <p>An initialised repository with commits always has a HEAD, so the fallback only
     * matters for a repository that was initialised and never committed to.
     */
    private static List<CommitRef> historyOf(Repository repository) {
        List<Commit> commits = repository.head()
                .map(head -> new ArrayList<>(repository.history(head.id())))
                .orElseGet(() -> new ArrayList<>(
                        repository.commitIds().stream().map(repository::commit).toList()));
        return commits.stream().map(CommitRef::of).toList();
    }
}
