package com.vvcs.repository;

import java.time.Instant;

/**
 * What {@code .vvcs/repository.json} holds: which layout version this repository was written
 * with, when it was created, and who is committing to it by default.
 *
 * <p>The format version is what lets a future VVCS recognise a repository it has to migrate
 * instead of one it can read as it stands.
 */
public record RepositoryMetadata(int formatVersion, Instant createdAt, String author) {

    /**
     * The layout this version of VVCS writes and understands.
     */
    public static final int CURRENT_FORMAT_VERSION = 1;

    public RepositoryMetadata {
        if (formatVersion < 1) {
            throw new IllegalArgumentException("unsupported repository format version: " + formatVersion);
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("repository must record when it was created");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("repository must record a default author");
        }
    }
}
