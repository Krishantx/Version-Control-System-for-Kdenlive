package com.vvcs.comparison;

import com.vvcs.diff.DiffFixtures;
import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.SourceRange;
import com.vvcs.model.TimeRange;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;
import com.vvcs.repository.RepositoryException;
import com.vvcs.workflow.CommitWorkflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two commit ids in, a comparison out.
 *
 * <p>This is the seam a CLI, a web API and a test all share, so what matters is that the
 * caller does not have to know a commit id has to be resolved before two states can be
 * compared, and that nothing of the repository or the editor survives into the result.
 */
class TimelineComparisonServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"),
            ZoneOffset.UTC);
    private static final String AUTHOR = "editor@example.com";

    @Test
    @DisplayName("two commit ids are enough to obtain a comparison")
    void comparesTwoCommits(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);
        Repository repository = new FileSystemRepository(work, CLOCK);

        TimelineComparison comparison =
                new TimelineComparisonService(repository).compare(pair.first(), pair.second());

        assertAll(
                () -> assertNotNull(comparison.before()),
                () -> assertNotNull(comparison.after()),
                () -> assertNotNull(comparison.changes()),
                () -> assertEquals(pair.first(), comparison.from().id()),
                () -> assertEquals(pair.second(), comparison.to().id()),
                // The state ids are the repository's own content hashes, so a caller can
                // cross-check them against a checkout
                () -> assertEquals(64, comparison.before().stateId().length()),
                () -> assertEquals(64, comparison.after().stateId().length()));
    }

    @Test
    @DisplayName("a short id resolves to the same comparison as the full one")
    void shortIdsWork(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);
        Repository repository = new FileSystemRepository(work, CLOCK);
        TimelineComparisonService service = new TimelineComparisonService(repository);

        TimelineComparison byFull = service.compare(pair.first(), pair.second());
        TimelineComparison byShort = service.compare(
                repository.commit(pair.first()).shortId(),
                repository.commit(pair.second()).shortId());

        assertEquals(byFull, byShort);
    }

    @Test
    @DisplayName("the commits are labelled with what a person reads")
    void commitsAreLabelled(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);

        TimelineComparison comparison = new TimelineComparisonService(
                new FileSystemRepository(work, CLOCK)).compare(pair.first(), pair.second());

        assertAll(
                () -> assertEquals("first cut", comparison.from().message()),
                () -> assertEquals("second cut", comparison.to().message()),
                () -> assertEquals(AUTHOR, comparison.from().author()),
                () -> assertEquals(AUTHOR, comparison.to().author()),
                () -> assertNotNull(comparison.from().timestamp()),
                () -> assertNotNull(comparison.to().timestamp()));
    }

    @Test
    @DisplayName("a comparison of a commit with itself is empty but still labelled")
    void commitWithItself(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);

        TimelineComparison comparison = new TimelineComparisonService(
                new FileSystemRepository(work, CLOCK)).compare(pair.first(), pair.first());

        assertAll(
                () -> assertTrue(comparison.isEmpty()),
                () -> assertNotNull(comparison.before()),
                () -> assertNotNull(comparison.after()),
                () -> assertEquals(pair.first(), comparison.from().id()),
                () -> assertEquals(pair.first(), comparison.to().id()));
    }

    @Test
    @DisplayName("an unknown commit is refused rather than answered with a guess")
    void unknownCommitIsRefused(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);
        TimelineComparisonService service =
                new TimelineComparisonService(new FileSystemRepository(work, CLOCK));

        assertThrows(RepositoryException.class, () -> service.compare("deadbeef", pair.second()));
        assertThrows(RepositoryException.class, () -> service.compare(pair.first(), "deadbeef"));
    }

    @Test
    @DisplayName("comparing two states needs no repository and yields no commit labels")
    void statesAloneNeedNoRepository(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);
        Repository repository = new FileSystemRepository(work, CLOCK);

        TimelineComparison comparison = new TimelineComparisonService(repository).compare(
                repository.state(repository.commit(pair.first()).stateId()),
                repository.state(repository.commit(pair.second()).stateId()));

        assertAll(
                () -> assertNotNull(comparison.before()),
                () -> assertNotNull(comparison.after()),
                () -> assertNull(comparison.from()),
                () -> assertNull(comparison.to()),
                () -> assertFalse(comparison.isEmpty()));
    }

    @Test
    @DisplayName("a single state can be snapshotted, for a view with nothing to compare against")
    void snapshotOfOneState(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);
        Repository repository = new FileSystemRepository(work, CLOCK);

        TimelineSnapshot snapshot = new TimelineComparisonService(repository)
                .snapshot(repository.state(repository.commit(pair.first()).stateId()));

        assertAll(
                () -> assertNotNull(snapshot.stateId()),
                () -> assertEquals(1, snapshot.trackCount()),
                () -> assertEquals(1, snapshot.clipCount()),
                () -> assertEquals("demo", snapshot.projectName()));
    }

    @Test
    @DisplayName("the comparison is the engine's, not a second opinion")
    void usesTheSingleEngine(@TempDir Path work) {
        CommitPair pair = repositoryWithTwoStates(work);
        Repository repository = new FileSystemRepository(work, CLOCK);
        var engine = new com.vvcs.diff.DiffEngine();
        var service = new TimelineComparisonService(repository, engine);

        TimelineComparison viaService = service.compare(pair.first(), pair.second());
        var older = repository.state(repository.commit(pair.first()).stateId());
        var newer = repository.state(repository.commit(pair.second()).stateId());
        var expected = engine.diff(older.id(), newer.id(), older.project(), newer.project());

        assertAll(
                // The very object the engine produced, unchanged, including the state ids the
                // service supplied rather than inventing
                () -> assertEquals(expected, viaService.changes()),
                () -> assertEquals(older.id(), viaService.changes().fromStateId()),
                () -> assertEquals(newer.id(), viaService.changes().toStateId()));
    }

    // -------------------------------------------------------------------------------------

    private record CommitPair(String first, String second) {
    }

    /** A repository holding one project committed twice, the second time one clip shorter. */
    private CommitPair repositoryWithTwoStates(Path work) {
        Repository repository = new FileSystemRepository(work, CLOCK);
        repository.initialize(AUTHOR);
        CommitWorkflow workflow = new CommitWorkflow(repository, CLOCK);
        workflow.commit(base(), "first cut", AUTHOR);
        workflow.commit(shorter(), "second cut", AUTHOR);

        List<String> ids = new ArrayList<>();
        for (var commit : repository.history(repository.head().orElseThrow().id())) {
            ids.add(commit.id());
        }
        // history runs newest first
        return new CommitPair(ids.get(1), ids.get(0));
    }

    private static Project base() {
        return DiffFixtures.project(DiffFixtures.video("track_001",
                DiffFixtures.clip("clip_001", "e1", "asset_1", "track_001", 0, 50)));
    }

    /** The same project with its only clip trimmed to 30 frames. */
    private static Project shorter() {
        return DiffFixtures.project(DiffFixtures.video("track_001",
                new Clip("clip_001", "asset_1", "track_001",
                        new SourceRange(0, 29, 30, DiffFixtures.FPS),
                        new TimeRange(0, 30), "e1")));
    }
}
