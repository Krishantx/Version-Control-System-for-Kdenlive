package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One entry in the linear history of a project.
 *
 * <p>A commit names a {@link ProjectState} and points at the commit that preceded it, which
 * is what makes history walkable and what will later allow a commit graph to be introduced
 * without changing this type.
 *
 * <p>The {@code id} is not chosen, it is derived: it is the SHA-256 of everything else in
 * this record together with the digest of the state it names. Two commits that differ in
 * any of those inputs necessarily have different ids, and a commit whose contents are
 * unchanged keeps its id. The derivation itself lives in {@code com.vvcs.hash.CommitHasher}
 * so that this record stays free of any storage or hashing concern.
 */
public record Commit(
        String id,
        String parentId,
        String stateId,
        String author,
        String message,
        Instant timestamp) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public Commit {
        if (id == null || !SHA256.matcher(id).matches()) {
            throw new IllegalArgumentException("commit id must be a lowercase sha-256 digest: " + id);
        }
        if (parentId != null && !SHA256.matcher(parentId).matches()) {
            throw new IllegalArgumentException("parent id must be a lowercase sha-256 digest: " + parentId);
        }
        if (stateId == null || stateId.isBlank()) {
            throw new IllegalArgumentException("commit " + id + " has no state");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("commit " + id + " has no author");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("commit " + id + " has no message");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("commit " + id + " has no timestamp");
        }
    }

    /**
     * Whether this is the first commit in the history, the one with nothing before it.
     */
    @JsonIgnore
    public boolean isRoot() {
        return parentId == null;
    }

    /**
     * The abbreviated form of the id, as shown by the log.
     */
    @JsonIgnore
    public String shortId() {
        return id.substring(0, 8);
    }
}
