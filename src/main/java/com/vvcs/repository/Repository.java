package com.vvcs.repository;

import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stores and retrieves the two things VVCS keeps: immutable project states and the commits
 * that name them.
 *
 * <p>Deliberately knows nothing about Kdenlive, or about any other editor. A repository
 * receives states that have already been produced, which is what allows a Premiere or Resolve
 * adapter to feed exactly the same repository later.
 *
 * <p>Everything a repository returns comes from disk. A freshly constructed repository over
 * an existing directory knows the same things as one that has been open all along.
 *
 * <p>Storing an object that is already stored is a no-op, because states and commits are
 * immutable and identical content has an identical identity. Storing an object whose
 * identity is already taken by <em>different</em> content is an error.
 */
public interface Repository {

    /**
     * The directory this repository governs.
     */
    Path root();

    /**
     * Creates the repository layout, recording {@code author} as the default committer.
     * Fails if a repository already exists.
     *
     * @throws RepositoryException if a repository is already present
     */
    void initialize(String author);

    /**
     * The author new commits are attributed to unless the caller says otherwise.
     */
    String defaultAuthor();

    /**
     * Whether {@link #initialize()} has been performed and the layout is intact.
     */
    boolean isInitialized();

    /**
     * The identifier {@code project} is stored under, derived from its content alone.
     *
     * <p>Two commits of the same edit produce the same identifier without needing to be
     * compared, which is how a repository avoids storing the same state twice.
     */
    String stateIdFor(Project project);

    /**
     * Writes a state, unless an identical one is already stored.
     *
     * @throws RepositoryException if the identity is taken by different content, or if the
     *                             state is not stored under the identity its content
     *                             implies
     */
    void storeState(ProjectState state);

    /**
     * Reads a state by its identifier.
     *
     * @throws RepositoryException if no such state is stored
     */
    ProjectState state(String stateId);

    /**
     * Every stored state identifier, in sorted order.
     */
    List<String> stateIds();

    /**
     * Writes a commit, unless an identical one is already stored.
     *
     * @throws RepositoryException if the identity is taken by different content
     */
    void storeCommit(Commit commit);

    /**
     * Reads a commit by its full identifier or by any unambiguous prefix of it.
     *
     * @throws RepositoryException if there is no such commit, or the prefix is ambiguous
     */
    Commit commit(String commitIdOrPrefix);

    /**
     * Every stored commit identifier, oldest first.
     */
    List<String> commitIds();

    /**
     * The commit history reachable from {@code fromId}, newest first. The walk follows
     * {@code parentId} and stops at a root commit.
     *
     * @throws RepositoryException if the history is not a single unbroken chain
     */
    List<Commit> history(String fromId);

    /**
     * The current commit, or empty when nothing has been committed yet.
     *
     * @throws RepositoryException if HEAD points at a commit that is not stored
     */
    Optional<Commit> head();

    /**
     * Moves HEAD to an existing commit.
     */
    void setHead(String commitId);
}
