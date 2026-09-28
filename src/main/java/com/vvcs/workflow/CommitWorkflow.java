package com.vvcs.workflow;

import com.vvcs.hash.CommitHasher;
import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import com.vvcs.repository.Repository;

import java.time.Clock;

/**
 * Freezes a project into a state, attaches that state to a commit, and advances the
 * repository. This is where the chain the architecture depends on actually runs:
 *
 * <pre>
 * KdenliveParser -&gt; Project -&gt; ProjectState -&gt; Commit -&gt; Repository
 * </pre>
 *
 * <p>Nothing below this class knows that the project came from Kdenlive. A Premiere or
 * Resolve adapter produces the same {@code Project} and reaches this class unchanged, which
 * is the point of keeping the conversion here rather than inside the repository.
 *
 * <p>No comparison against the previous state is made. Every commit records a complete state,
 * so committing an unchanged project is allowed and simply produces a new commit whose
 * parent is the previous HEAD. That commit reuses the existing state, because a state is
 * named by its content and the content has not changed.
 */
public final class CommitWorkflow {

    private final Repository repository;
    private final Clock clock;

    public CommitWorkflow(Repository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records {@code project} as a new state and commits it.
     *
     * @param message what the change was, must not be blank
     * @param author  who is making the change, or {@code null} to use the repository default
     * @return the commit that was created and made current
     */
    public Commit commit(Project project, String message, String author) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("a commit needs a message");
        }
        if (project == null) {
            throw new IllegalArgumentException("cannot commit a null project");
        }

        String commitAuthor = author == null || author.isBlank()
                ? repository.defaultAuthor()
                : author;

        // The id follows from the content, so the same edit always lands on the same state
        // and storing it twice is a no-op rather than a duplicate.
        ProjectState state = ProjectState.of(repository.stateIdFor(project), project);
        repository.storeState(state);

        // Read the clock exactly once: the timestamp that is hashed must be the same
        // timestamp that is stored, or the id would not match its own contents.
        var timestamp = clock.instant();
        String parentId = repository.head().map(Commit::id).orElse(null);
        String commitId = CommitHasher.idOf(parentId, state.id(), commitAuthor, message,
                timestamp);

        Commit commit = new Commit(commitId, parentId, state.id(), commitAuthor, message,
                timestamp);
        repository.storeCommit(commit);
        repository.setHead(commit.id());

        return commit;
    }
}
