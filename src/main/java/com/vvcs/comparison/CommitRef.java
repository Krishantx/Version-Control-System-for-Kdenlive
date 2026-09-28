package com.vvcs.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vvcs.model.Commit;

import java.time.Instant;

/**
 * The identifying facts of a commit, without the commit itself.
 *
 * <p>A consumer showing two states side by side needs to label them with who made them, when,
 * and why. Handing it the whole {@link Commit} would expose a {@code parentId} and a
 * {@code stateId} that are meaningful only to the repository; this carries what a person or
 * an interface actually reads.
 *
 * <p>Null when a comparison was made between two states without going through a repository,
 * in which case there is no commit to describe.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommitRef(
        String id,
        String shortId,
        String stateId,
        String author,
        String message,
        Instant timestamp) {

    public static CommitRef of(Commit commit) {
        return new CommitRef(commit.id(), commit.shortId(), commit.stateId(),
                commit.author(), commit.message(), commit.timestamp());
    }
}
