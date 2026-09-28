package com.vvcs.hash;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vvcs.serialization.VvcsJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * Derives the identity of a commit.
 *
 * <p>A commit id is the SHA-256 of a canonical JSON encoding of everything that defines the
 * commit: its parent, the state it names, the author, the message and the timestamp.
 * Encoding the payload as JSON rather than joining fields with a separator means a message
 * containing a separator cannot be made to collide with a different message. Including the
 * timestamp is what makes two otherwise identical commits distinct, which is what the spec
 * asks for.
 *
 * <p>The state is named by its id rather than by its content, because the id already
 * <em>is</em> the content's digest (see {@link StateHasher}); hashing the content a second
 * time would prove nothing and make the payload harder to read.
 *
 * <p>The digest is taken over {@link VvcsJson#canonical()}, which carries no insignificant
 * whitespace, so the id depends on the content and never on how pretty the file looks.
 */
public final class CommitHasher {

    private CommitHasher() {
    }

    public static String idOf(String parentId, String stateId, String author, String message,
                              Instant timestamp) {
        Payload payload = new Payload(author, message, parentId, stateId, timestamp);
        try {
            return ContentHash.sha256Utf8(VvcsJson.toCanonicalJson(payload));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot derive commit id", e);
        }
    }

    /**
     * The exact set of fields that make up a commit identity. Property order is fixed by the
     * annotation and further pinned by the canonical mapper, so the encoded bytes are
     * stable. The commit's own id is absent because it is the output of this encoding.
     */
    @JsonPropertyOrder({"author", "message", "parentId", "stateId", "timestamp"})
    private record Payload(
            String author,
            String message,
            String parentId,
            String stateId,
            Instant timestamp) {
    }
}
