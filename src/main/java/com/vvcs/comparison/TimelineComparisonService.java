package com.vvcs.comparison;

import com.vvcs.diff.DiffEngine;
import com.vvcs.diff.TimelineDiff;
import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import com.vvcs.model.Track;
import com.vvcs.repository.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns two points in a project's history into a {@link TimelineComparison}.
 *
 * <h2>What this is for</h2>
 *
 * <p>This is the seam between the core and whatever consumes it. A CLI, a web API and a test
 * all want the same thing, and none of them should have to know that a commit id has to be
 * resolved through a repository before two states can be compared. Each of them would
 * otherwise end up writing its own version of these four steps.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It does not compare anything. It contains no rule about what counts as a move, a trim
 * or a reorder: every one of those comes from {@link DiffEngine}, the single implementation
 * of them. This class only arranges the inputs, calls that engine, and arranges the result.
 * If the two snapshots in a comparison ever disagreed with its changes, the engine would be
 * the thing to look at, never this service.
 *
 * <h2>What it knows about</h2>
 *
 * <p>It knows about the repository, because resolving a commit id is its job. Everything
 * downstream of the comparison it returns knows about neither the repository nor the editor.
 */
public final class TimelineComparisonService {

    private final Repository repository;
    private final DiffEngine engine;

    public TimelineComparisonService(Repository repository) {
        this(repository, new DiffEngine());
    }

    /**
     * @param engine the one diff implementation, injectable only so that a test can prove the
     *               service adds no comparison logic of its own
     */
    public TimelineComparisonService(Repository repository, DiffEngine engine) {
        this.repository = repository;
        this.engine = engine;
    }

    /**
     * Compares two commits.
     *
     * @param commitA the state to compare from
     * @param commitB the state to compare to
     * @throws com.vvcs.repository.RepositoryException if either commit cannot be resolved
     */
    public TimelineComparison compare(String commitA, String commitB) {
        Commit from = repository.commit(commitA);
        Commit to = repository.commit(commitB);
        return compare(CommitRef.of(from), repository.state(from.stateId()),
                CommitRef.of(to), repository.state(to.stateId()));
    }

    /**
     * Compares two states that have already been resolved, for a caller holding states rather
     * than commits. There is no commit to label the result with.
     */
    public TimelineComparison compare(ProjectState before, ProjectState after) {
        return compare(null, before, null, after);
    }

    /**
     * Compares two already-differenced states, labelled with the commits they came from. This
     * is the step the other two both end at.
     */
    public TimelineComparison compare(CommitRef fromCommit, ProjectState before,
                                      CommitRef toCommit, ProjectState after) {
        TimelineDiff diff = engine.diff(before.id(), after.id(), before.project(), after.project());
        return new TimelineComparison(fromCommit, toCommit,
                snapshot(before), snapshot(after), diff);
    }

    /**
     * Flattens a state into a timeline snapshot.
     *
     * <p>Public because a consumer showing a single state, with nothing to compare it against,
     * needs the same shape and should not have to reimplement the flattening.
     */
    public TimelineSnapshot snapshot(ProjectState state) {
        return snapshot(state, state.project());
    }

    private static TimelineSnapshot snapshot(ProjectState state, Project project) {
        // Asset names are resolved once, up front: a clip refers to media by id, and a
        // consumer drawing a timeline wants to say what that media is called without having to
        // hold the asset list as well.
        Map<String, Asset> assetsById = new HashMap<>();
        for (Asset asset : project.assets()) {
            assetsById.put(asset.id(), asset);
        }

        List<TrackSnapshot> tracks = new ArrayList<>();
        List<Track> sourceTracks = project.timeline().tracks();
        for (int i = 0; i < sourceTracks.size(); i++) {
            tracks.add(track(sourceTracks.get(i), i, assetsById));
        }
        return new TimelineSnapshot(state.id(), project.name(),
                project.timeline().id(), project.timeline().name(),
                project.timeline().duration(), project.timeline().frameRate(),
                project.timeline().resolution(), tracks);
    }

    private static TrackSnapshot track(Track track, int index, Map<String, Asset> assetsById) {
        List<Clip> ordered = new ArrayList<>(track.clips());
        ordered.sort(java.util.Comparator.comparingLong(clip -> clip.timeline().start()));

        List<ClipSnapshot> clips = new ArrayList<>();
        for (Clip clip : ordered) {
            clips.add(clip(clip, assetsById));
        }
        return new TrackSnapshot(track.id(), track.type(), index, clips);
    }

    private static ClipSnapshot clip(Clip clip, Map<String, Asset> assetsById) {
        Asset asset = assetsById.get(clip.assetId());
        return new ClipSnapshot(
                clip.id(),
                clip.assetId(),
                asset == null ? null : asset.name(),
                clip.trackId(),
                clip.timeline().start(),
                clip.timeline().duration(),
                clip.source().inFrame(),
                clip.source().outFrame(),
                clip.source().timebase());
    }
}
