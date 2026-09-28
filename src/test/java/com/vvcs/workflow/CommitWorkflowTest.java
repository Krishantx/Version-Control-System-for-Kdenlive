package com.vvcs.workflow;

import com.vvcs.hash.CommitHasher;
import com.vvcs.model.Commit;
import com.vvcs.model.ProjectState;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;
import com.vvcs.repository.RepositoryException;
import com.vvcs.repository.TestProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit workflow is the place the whole chain meets: a project becomes a state, a
 * commit names that state, and the repository remembers it.
 */
class CommitWorkflowTest {

    /** A clock the test can move by hand, so ids are reproducible. */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    @TempDir
    Path workspace;

    private static int distinct(List<String> values) {
        return (int) values.stream().distinct().count();
    }

    private SteppingClock clock;
    private Repository repository;
    private CommitWorkflow workflow;

    @BeforeEach
    void initialise() {
        clock = new SteppingClock();
        repository = new FileSystemRepository(workspace, clock);
        repository.initialize("Krishant");
        workflow = new CommitWorkflow(repository, clock);
    }

    @Test
    void theFirstCommitHasNoParentAndBecomesHead() {
        Commit commit = workflow.commit(TestProjects.small(), "Initial edit", null);

        assertAll(
                () -> assertTrue(commit.isRoot()),
                () -> assertEquals(null, commit.parentId()),
                () -> assertEquals(repository.stateIdFor(TestProjects.small()),
                        commit.stateId(), "a state is named by its content"),
                () -> assertEquals("Krishant", commit.author(),
                        "the repository default is used when no author is given"),
                () -> assertEquals(commit, repository.head().orElseThrow()),
                () -> assertEquals(commit, repository.commit(commit.id())),
                () -> assertEquals(TestProjects.small(),
                        repository.state(commit.stateId()).project()));
    }

    @Test
    void theSecondCommitNamesTheFirstAsItsParentAndMovesHead() {
        Commit first = workflow.commit(TestProjects.small(), "Initial edit", null);
        clock.advanceSeconds(60);
        Commit second = workflow.commit(TestProjects.altered(), "Second edit", null);

        assertAll(
                () -> assertEquals(first.id(), second.parentId()),
                () -> assertEquals(repository.stateIdFor(TestProjects.altered()),
                        second.stateId()),
                () -> assertEquals(second, repository.head().orElseThrow()),
                () -> assertNotEquals(first.id(), second.id()),
                () -> assertEquals(List.of(second, first),
                        repository.history(second.id())));
    }

    @Test
    void historyGrowsAsASingleChain() {
        List<Commit> commits = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            clock.advanceSeconds(60);
            commits.add(workflow.commit(TestProjects.small(), "Edit " + i, null));
        }

        assertAll(
                () -> assertEquals(4, commits.size()),
                () -> assertEquals(4, repository.commitIds().size()),
                () -> assertEquals(1, distinct(commits.stream().map(Commit::stateId).toList()),
                        "every commit froze the same project, so all four share one state"),
                () -> assertEquals(4, distinct(commits.stream().map(Commit::id).toList()),
                        "each commit is still its own moment"),
                () -> assertTrue(commits.get(0).isRoot()),
                () -> assertEquals(1, repository.stateIds().size(),
                        "the same project committed four times is one stored state"),
                () -> assertTrue(commits.get(0).isRoot()),
                () -> assertEquals(commits.get(2).id(), commits.get(3).parentId()),
                () -> assertEquals(commits.get(1).id(), commits.get(2).parentId()),
                () -> assertEquals(commits.get(0).id(), commits.get(1).parentId()));
    }

    @Test
    void anExplicitAuthorOverridesTheRepositoryDefault() {
        Commit commit = workflow.commit(TestProjects.small(), "Initial edit", "Ana");
        assertEquals("Ana", commit.author());
    }

    @Test
    void aCommitMustSayWhatItDid() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> workflow.commit(TestProjects.small(), "   ", null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> workflow.commit(TestProjects.small(), null, null)));
    }

    @Test
    void committingNothingIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> workflow.commit(null, "Initial edit", null));
    }

    /**
     * Committing an unchanged project still makes a commit, because a commit records a moment
     * and a message as well as an edit. The state it points at is the one already stored,
     * which is the deduplication the content address buys.
     */
    @Test
    void committingAnUnchangedProjectReusesTheStoredState() {
        Commit first = workflow.commit(TestProjects.small(), "Initial edit", null);
        clock.advanceSeconds(60);
        Commit again = workflow.commit(TestProjects.small(), "Nothing changed", null);

        assertAll(
                () -> assertEquals(first.id(), again.parentId()),
                () -> assertNotEquals(first.id(), again.id(),
                        "a second commit is a second moment even without a new edit"),
                () -> assertEquals(first.stateId(), again.stateId(),
                        "the same project is the same state"),
                () -> assertEquals(List.of(first.stateId()), repository.stateIds(),
                        "the edit must not be stored twice"));
    }

    @Test
    void aCommitIsIdentifiedByItsOwnContents() {
        Commit commit = workflow.commit(TestProjects.small(), "Initial edit", null);

        String expected = CommitHasher.idOf(commit.parentId(), commit.stateId(), commit.author(),
                commit.message(), commit.timestamp());

        assertAll(
                () -> assertEquals(expected, commit.id(),
                        "the stored id must match the hash of what is stored"),
                () -> assertEquals(64, commit.id().length()));
    }

    @Test
    void committingWithoutARepositoryIsRefused() {
        CommitWorkflow orphan = new CommitWorkflow(
                new FileSystemRepository(workspace.resolve("nowhere"), clock), clock);
        assertTrue(assertThrows(RepositoryException.class,
                () -> orphan.commit(TestProjects.small(), "Initial edit", null))
                .getMessage().contains("vvcs init"));
    }
}
