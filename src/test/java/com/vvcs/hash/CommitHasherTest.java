package com.vvcs.hash;

import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import com.vvcs.repository.TestProjects;
import com.vvcs.serialization.VvcsJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commit identity has to be reproducible and collision resistant, because it is the only
 * thing that distinguishes one stored commit from another.
 */
class CommitHasherTest {

    private static final Instant WHEN = Instant.parse("2026-09-28T10:15:30.123456789Z");
    private static final String STATE_A = "a".repeat(64);
    private static final String STATE_B = "b".repeat(64);

    private static String idOf(String parent, String state, String author, String message,
                              Instant when) {
        return CommitHasher.idOf(parent, state, author, message, when);
    }

    @Test
    void producesASha256Digest() {
        assertEquals(64, idOf(null, STATE_A, "Krishant", "Initial edit", WHEN).length());
        assertTrue(idOf(null, STATE_A, "Krishant", "Initial edit", WHEN)
                .matches("[0-9a-f]{64}"));
    }

    @Test
    void isReproducibleForIdenticalContents() {
        assertEquals(
                idOf(null, STATE_A, "Krishant", "Initial edit", WHEN),
                idOf(null, STATE_A, "Krishant", "Initial edit", WHEN));
    }

    @Test
    void changesWhenTheTimestampChanges() {
        assertNotEquals(
                idOf(null, STATE_A, "Krishant", "Initial edit", WHEN),
                idOf(null, STATE_A, "Krishant", "Initial edit", WHEN.plusMillis(1)));
    }

    @Test
    void changesWhenAnyOtherInputChanges() {
        String base = idOf(null, STATE_A, "Krishant", "Initial edit", WHEN);
        assertAll(
                () -> assertNotEquals(base, idOf("f".repeat(64), STATE_A, "Krishant",
                        "Initial edit", WHEN), "parent"),
                () -> assertNotEquals(base, idOf(null, STATE_B, "Krishant",
                        "Initial edit", WHEN), "state id"),
                () -> assertNotEquals(base, idOf(null, STATE_A, "Someone else",
                        "Initial edit", WHEN), "author"),
                () -> assertNotEquals(base, idOf(null, STATE_A, "Krishant",
                        "Something else", WHEN), "message"));
    }

    /**
     * The payload is JSON rather than delimited text, so a separator inside a message cannot
     * be made to look like a field boundary.
     */
    @Test
    void cannotBeForcedToCollideByCraftingTheMessage() {
        String straight = idOf(null, STATE_A, "Krishant", "a|b", WHEN);
        String crafted = idOf(null, STATE_A, "Krishant", "a", WHEN);
        assertNotEquals(straight, crafted,
                "a separator in a message must not shift the fields that follow it");
    }

    @Test
    void aMessageContainingQuotesAndNewlinesIsSafe() {
        String message = "he said \"hi\"\nsecond line\ttabbed";
        String id = idOf(null, STATE_A, "Krishant", message, WHEN);
        Commit commit = new Commit(id, null, STATE_A, "Krishant", message, WHEN);
        assertEquals(id, CommitHasher.idOf(commit.parentId(), commit.stateId(), commit.author(),
                commit.message(), commit.timestamp()),
                "an id must be recoverable from the commit it names");
    }

    @Test
    void theIdIsRecoverableFromTheStoredCommit() {
        String id = idOf("c".repeat(64), STATE_B, "Ana", "Trim intro", WHEN);
        Commit commit = new Commit(id, "c".repeat(64), STATE_B, "Ana", "Trim intro", WHEN);
        assertEquals(id, CommitHasher.idOf(commit.parentId(), commit.stateId(), commit.author(),
                commit.message(), commit.timestamp()));
    }

    // ------------------------------------------------------- state identity

    @Test
    void aStateIdIsTheDigestOfItsContent() {
        Project project = TestProjects.small();

        assertAll(
                () -> assertEquals(64, StateHasher.idOf(project).length()),
                () -> assertTrue(StateHasher.idOf(project).matches("[0-9a-f]{64}")),
                () -> assertEquals(StateHasher.idOf(project), StateHasher.idOf(project),
                        "state ids must be reproducible"),
                () -> assertNotEquals(StateHasher.idOf(project),
                        StateHasher.idOf(TestProjects.altered()),
                        "different content must produce a different identifier"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> StateHasher.idOf(null)));
    }

    @Test
    void aStateIdDoesNotDependOnTheStateIdentifier() {
        Project project = TestProjects.small();
        String derived = StateHasher.idOf(project);

        assertAll(
                () -> assertTrue(StateHasher.matchesIdOf(project, derived)),
                () -> assertFalse(StateHasher.matchesIdOf(TestProjects.altered(), derived)),
                () -> assertFalse(StateHasher.matchesIdOf(project, "a".repeat(64))));
    }

    /**
     * Two states holding the same project are the same state, which is what lets a repository
     * store the edit once however many commits point at it.
     */
    @Test
    void theSameEditUnderDifferentNamesIsTheSameState() {
        Project project = TestProjects.small();
        ProjectState first = ProjectState.of(StateHasher.idOf(project), project);
        ProjectState second = ProjectState.of(StateHasher.idOf(project), project);

        assertEquals(first, second);
    }

    /**
     * Commit ids are hashed over the canonical writer's output, never the indented one, so
     * that changing how stored files are pretty printed cannot change the identity of commits
     * that already exist.
     */
    @Test
    void canonicalOutputCarriesNoInsignificantWhitespace() throws IOException {
        ProjectState state = ProjectState.of(StateHasher.idOf(TestProjects.small()),
                TestProjects.small());
        String canonical = VvcsJson.toCanonicalJson(state);
        String readable = VvcsJson.toJson(state);

        assertAll(
                () -> assertTrue(canonical.lines().count() > 0),
                () -> assertTrue(!canonical.contains("\n  "), "canonical output must not indent"),
                () -> assertTrue(readable.contains("\n  "), "stored files must stay readable"),
                () -> assertEquals(state, VvcsJson.read(readable, ProjectState.class),
                        "both shapes must still describe the same state"));
    }
}
