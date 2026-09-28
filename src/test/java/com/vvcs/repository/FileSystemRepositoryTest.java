package com.vvcs.repository;

import com.vvcs.hash.CommitHasher;
import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository's job is to be a faithful, restartable store of immutable objects.
 *
 * <p>State identifiers are content addresses here, so the fixtures build states through
 * {@link #stateFor(Project)} rather than naming them, which is what the production code does.
 */
class FileSystemRepositoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"),
            ZoneOffset.UTC);

    @TempDir
    Path workspace;

    private Repository repository;

    @BeforeEach
    void createRepository() {
        repository = new FileSystemRepository(workspace, CLOCK);
        repository.initialize("Krishant");
    }

    private ProjectState stateFor(Project project) {
        return ProjectState.of(repository.stateIdFor(project), project);
    }

    // ----------------------------------------------------------------- layout

    @Test
    void initializeCreatesTheExpectedLayout() {
        Path vvcs = workspace.resolve(".vvcs");
        assertAll(
                () -> assertTrue(Files.isDirectory(vvcs)),
                () -> assertTrue(Files.isRegularFile(vvcs.resolve("repository.json"))),
                () -> assertTrue(Files.isDirectory(vvcs.resolve("states"))),
                () -> assertTrue(Files.isDirectory(vvcs.resolve("commits"))),
                () -> assertTrue(Files.isRegularFile(vvcs.resolve("HEAD")),
                        "the layout includes HEAD; it is empty, not absent, until a commit"),
                () -> assertEquals("", Files.readString(vvcs.resolve("HEAD"))),
                () -> assertTrue(repository.isInitialized()));
    }

    @Test
    void initializeRefusesToOverwriteAnExistingRepository() {
        RepositoryException failure = assertThrows(RepositoryException.class,
                () -> repository.initialize("Someone else"));
        assertTrue(failure.getMessage().contains("already exists"));
        assertEquals("Krishant", repository.defaultAuthor(),
                "the original author must survive a refused re-initialisation");
    }

    @Test
    void anUninitialisedDirectoryReportsEveryOperationAsUnusable() {
        Repository empty = new FileSystemRepository(workspace.resolve("elsewhere"), CLOCK);
        assertAll(
                () -> assertFalse(empty.isInitialized()),
                () -> assertTrue(assertThrows(RepositoryException.class, empty::stateIds)
                        .getMessage().contains("vvcs init")));
    }

    @Test
    void metadataRecordsTheFormatAndTheAuthor() {
        assertAll(
                () -> assertEquals("Krishant", repository.defaultAuthor()),
                () -> assertTrue(Files.readString(
                        workspace.resolve(".vvcs").resolve("repository.json"))
                        .contains("\"formatVersion\"")));
    }

    // ----------------------------------------------------------------- states

    @Test
    void aStateIsIdentifiedByItsContent() {
        String id = repository.stateIdFor(TestProjects.small());

        assertAll(
                () -> assertEquals(64, id.length(), "a full sha-256 digest is used"),
                () -> assertTrue(id.matches("[0-9a-f]{64}")),
                () -> assertEquals(id, repository.stateIdFor(TestProjects.small()),
                        "the same project must always produce the same identifier"),
                () -> assertNotEquals(id, repository.stateIdFor(TestProjects.altered()),
                        "a different project must produce a different identifier"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> repository.stateIdFor(null)));
    }

    @Test
    void aStoredStateCanBeReadBackUnchanged() {
        ProjectState state = stateFor(TestProjects.small());
        repository.storeState(state);

        assertAll(
                () -> assertEquals(state, repository.state(state.id())),
                () -> assertEquals(List.of(state.id()), repository.stateIds()),
                () -> assertEquals(state.project(), repository.state(state.id()).project()));
    }

    @Test
    void statesAreSeparateFilesRatherThanOneGiantDocument() {
        ProjectState first = stateFor(TestProjects.small());
        repository.storeState(first);
        ProjectState second = stateFor(TestProjects.altered());
        repository.storeState(second);

        assertAll(
                () -> assertTrue(Files.isRegularFile(
                        workspace.resolve(".vvcs/states").resolve(first.id() + ".json"))),
                () -> assertTrue(Files.isRegularFile(
                        workspace.resolve(".vvcs/states").resolve(second.id() + ".json"))),
                () -> assertEquals(2, repository.stateIds().size()));
    }

    /**
     * The point of content addressing: the same edit is stored once however often it is
     * committed.
     */
    @Test
    void storingTheSameStateTwiceIsHarmless() {
        ProjectState state = stateFor(TestProjects.small());
        repository.storeState(state);
        repository.storeState(state);

        assertEquals(List.of(state.id()), repository.stateIds());
    }

    @Test
    void aStateStoredUnderTheWrongIdentifierIsRefused() {
        String wrong = "f".repeat(64);
        ProjectState mislabelled = ProjectState.of(wrong, TestProjects.small());

        assertAll(
                () -> assertTrue(assertThrows(RepositoryException.class,
                        () -> repository.storeState(mislabelled))
                        .getMessage().contains("does not match the content")),
                () -> assertEquals(List.of(), repository.stateIds(),
                        "a refused state must not be written"),
                () -> assertTrue(assertThrows(RepositoryException.class,
                        () -> repository.state(wrong)).getMessage().contains("no such state")));
    }

    @Test
    void readingAMissingStateFails() {
        assertTrue(assertThrows(RepositoryException.class, () -> repository.state("a".repeat(64)))
                .getMessage().contains("a".repeat(8)));
    }

    // ---------------------------------------------------------------- commits

    @Test
    void aStoredCommitCanBeReadBackUnchanged() {
        Commit commit = commit(null, "First", 0);
        repository.storeCommit(commit);

        assertAll(
                () -> assertEquals(commit, repository.commit(commit.id())),
                () -> assertEquals(commit, repository.commit(commit.shortId()),
                        "a unique prefix must resolve"),
                () -> assertTrue(commit.isRoot()),
                () -> assertEquals(List.of(commit.id()), repository.commitIds()));
    }

    @Test
    void anUnknownPrefixIsRejected() {
        repository.storeCommit(commit(null, "First", 0));

        assertAll(
                () -> assertTrue(assertThrows(RepositoryException.class,
                        () -> repository.commit("deadbee")).getMessage().contains("no such commit")),
                () -> assertTrue(assertThrows(RepositoryException.class,
                        () -> repository.commit("")).getMessage().contains("no commit id")));
    }

    /**
     * Real digests collide on a two character prefix only by luck, so the two commits that
     * share one are written directly.
     */
    @Test
    void anAmbiguousPrefixIsRejected() throws IOException {
        storeCommitFileWithId("ab" + "0".repeat(62), "One");
        storeCommitFileWithId("ab" + "1".repeat(62), "Two");

        assertAll(
                () -> assertTrue(assertThrows(RepositoryException.class,
                        () -> repository.commit("ab"))
                        .getMessage().contains("ambiguous")),
                () -> assertEquals("One", repository.commit("ab" + "0".repeat(62)).message(),
                        "a longer unambiguous prefix must still resolve"));
    }

    private void storeCommitFileWithId(String id, String message) throws IOException {
        String json = "{\"id\":\"" + id + "\",\"parentId\":null,\"stateId\":\""
                + "a".repeat(64) + "\","
                + "\"author\":\"Krishant\",\"message\":\"" + message
                + "\",\"timestamp\":\"2026-09-28T10:00:00Z\"}";
        Files.writeString(workspace.resolve(".vvcs/commits").resolve(id + ".json"), json,
                StandardCharsets.UTF_8);
    }

    @Test
    void anIdentityHeldByDifferentContentIsNotOverwritten() throws IOException {
        Commit commit = commit(null, "Initial edit", 0);
        repository.storeCommit(commit);
        // Same id, different content: only reachable by tampering, which must be refused.
        Files.writeString(workspace.resolve(".vvcs/commits").resolve(commit.id() + ".json"),
                "{\"tampered\":true}", StandardCharsets.UTF_8);

        assertTrue(assertThrows(RepositoryException.class, () -> repository.storeCommit(commit))
                .getMessage().contains("different content"));
    }

    // ------------------------------------------------------------------- HEAD

    @Test
    void headIsEmptyUntilACommitIsStored() {
        assertEquals(Optional.empty(), repository.head());
    }

    @Test
    void headMovesToTheCommitThatWasJustStored() {
        Commit first = commit(null, "First", 0);
        Commit second = commit(first.id(), "Second", 1);
        repository.storeCommit(first);
        repository.storeCommit(second);

        repository.setHead(first.id());
        assertEquals(first, repository.head().orElseThrow());
        repository.setHead(second.id());
        assertEquals(second, repository.head().orElseThrow());
    }

    @Test
    void headCannotBePointedAtACommitThatDoesNotExist() {
        assertTrue(assertThrows(RepositoryException.class,
                () -> repository.setHead("c".repeat(64)))
                .getMessage().contains("unknown commit"));
    }

    /**
     * A HEAD pointing at nothing is damage, not an empty history, and must not be silently
     * read as "no commits yet".
     */
    @Test
    void aDanglingHeadIsReportedAsDamage() throws IOException {
        Commit commit = commit(null, "First", 0);
        repository.storeCommit(commit);
        repository.setHead(commit.id());
        Files.delete(workspace.resolve(".vvcs/commits").resolve(commit.id() + ".json"));

        assertTrue(assertThrows(RepositoryException.class, repository::head)
                .getMessage().contains("not stored"));
    }

    // ---------------------------------------------------------------- history

    @Test
    void historyFollowsParentsNewestFirst() {
        Commit first = commit(null, "First", 0);
        Commit second = commit(first.id(), "Second", 1);
        Commit third = commit(second.id(), "Third", 2);
        for (Commit c : List.of(first, second, third)) {
            repository.storeCommit(c);
        }

        assertEquals(List.of("Third", "Second", "First"),
                repository.history(third.id()).stream().map(Commit::message).toList());
        assertEquals(List.of(third, second, first), repository.history(third.id()));
    }

    @Test
    void historyOfASingleCommitIsJustThatCommit() {
        Commit only = commit(null, "Only", 0);
        repository.storeCommit(only);
        assertEquals(List.of(only), repository.history(only.id()));
    }

    /** A commit naming a parent that is not stored is a broken chain, not an empty one. */
    @Test
    void aHistoryWithAMissingAncestorFails() {
        Commit orphan = commit("a".repeat(64), "Orphan", 1);
        repository.storeCommit(orphan);
        assertTrue(assertThrows(RepositoryException.class, () -> repository.history(orphan.id()))
                .getMessage().contains("no such commit"));
    }

    // ------------------------------------------------------------- reopening

    @Test
    void everythingIsRecoverableFromDiskAlone() {
        ProjectState state = stateFor(TestProjects.small());
        Commit commit = commit(null, "Initial edit", 0);
        repository.storeState(state);
        repository.storeCommit(commit);
        repository.setHead(commit.id());

        Repository reopened = new FileSystemRepository(workspace, CLOCK);

        assertAll(
                () -> assertTrue(reopened.isInitialized()),
                () -> assertEquals(commit, reopened.head().orElseThrow()),
                () -> assertEquals(state, reopened.state(state.id())),
                () -> assertEquals(List.of(state.id()), reopened.stateIds()),
                () -> assertEquals(List.of(commit), reopened.history(commit.id())),
                () -> assertEquals("Krishant", reopened.defaultAuthor()));
    }

    // -------------------------------------------------------------- utilities

    private Commit commit(String parentId, String message, int minutesAfter) {
        Instant when = CLOCK.instant().plusSeconds(minutesAfter * 60L);
        String stateId = "a".repeat(64);
        String id = CommitHasher.idOf(parentId, stateId, "Krishant", message, when);
        return new Commit(id, parentId, stateId, "Krishant", message, when);
    }
}
