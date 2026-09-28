package com.vvcs.diff;

import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;
import com.vvcs.model.SourceRange;
import com.vvcs.model.TimeRange;
import com.vvcs.model.TimeSpan;
import com.vvcs.model.Timeline;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds small projects by hand so that a diff test can state its starting arrangement
 * without depending on a real Kdenlive file.
 */
public final class DiffFixtures {

    public static final Rational FPS = new Rational(30, 1);
    public static final Resolution HD = new Resolution(1920, 1080);

    private DiffFixtures() {
    }

    public static Asset asset(String id) {
        return new Asset(id, id + ".mp4", "/media/" + id + ".mp4", "hash-" + id,
                new TimeSpan(1000, FPS), HD, "h264", "aac");
    }

    /**
     * A clip with an editor-provided occurrence id, which is what a real parse carries.
     */
    public static Clip clip(String id, String sourceId, String assetId, String trackId,
                     long start, long duration, long in, long out) {
        return new Clip(id, assetId, trackId,
                new SourceRange(in, out, out - in + 1, FPS),
                new TimeRange(start, duration),
                sourceId);
    }

    /** The common case: a clip occupying {@code [start, start + length)} of its source. */
    public static Clip clip(String id, String sourceId, String assetId, String trackId,
                     long start, long length) {
        return clip(id, sourceId, assetId, trackId, start, length, start, start + length - 1);
    }

    /**
     * A clip that keeps the same part of its source and only sits somewhere else on the
     * timeline. Moving a clip does not change which frames of the media it uses, and a test
     * about movement must not report a trim as a side effect.
     */
    public static Clip placedAt(String id, String sourceId, String assetId, String trackId,
                         long start, long length, long sourceIn) {
        return clip(id, sourceId, assetId, trackId, start, length, sourceIn, sourceIn + length - 1);
    }

    public static Track video(String id, Clip... clips) {
        return new Track(id, TrackType.VIDEO, List.of(clips));
    }

    public static Track audio(String id, Clip... clips) {
        return new Track(id, TrackType.AUDIO, List.of(clips));
    }

    public static Project project(Track... tracks) {
        return project(List.of(asset("asset_1"), asset("asset_2"), asset("asset_3")), tracks);
    }

    public static Project project(List<Asset> assets, Track... tracks) {
        long duration = 0;
        for (Track track : tracks) {
            duration = Math.max(duration, track.endFrame());
        }
        Timeline timeline = new Timeline("timeline_1", "Main", FPS, HD, duration, List.of(tracks));
        return new Project("demo", HD, FPS, assets, timeline);
    }

    /**
     * A project in which clip A sits before clip B on the video track, both on asset_1.
     */
    public static Project twoClipsInOrder() {
        return project(
                video("track_001",
                        clip("clip_001", "e1", "asset_1", "track_001", 0, 50),
                        clip("clip_002", "e2", "asset_1", "track_001", 50, 50)));
    }

    /**
     * The same media and the same occurrences as {@link #twoClipsInOrder()}, but B has been
     * dragged in front of A. The two clips differ only in how much of the source they use,
     * which is what makes the reorder detectable at all.
     */
    public static Project twoClipsReordered() {
        return project(
                video("track_001",
                        placedAt("clip_001", "e2", "asset_1", "track_001", 0, 30, 200),
                        placedAt("clip_002", "e1", "asset_1", "track_001", 30, 50, 0)));
    }

    /** Replaces a clip in a project, matching on the editor-provided occurrence id. */
    public static Project replacingClip(Project base, Clip replacement) {
        List<Track> tracks = new ArrayList<>();
        for (Track track : base.timeline().tracks()) {
            List<Clip> clips = new ArrayList<>();
            boolean found = false;
            for (Clip clip : track.clips()) {
                if (replacement.sourceId() != null
                        && replacement.sourceId().equals(clip.sourceId())) {
                    clips.add(replacement);
                    found = true;
                } else {
                    clips.add(clip);
                }
            }
            if (found) {
                tracks.add(new Track(track.id(), track.type(), clips));
            } else {
                tracks.add(track);
            }
        }
        return project(base.assets(), tracks.toArray(new Track[0]));
    }
}
