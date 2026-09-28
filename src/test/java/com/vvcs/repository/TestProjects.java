package com.vvcs.repository;

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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Projects for tests that are about the repository rather than about parsing.
 *
 * <p>Repository behaviour does not depend on any particular project, so these tests use a
 * small hand-built one. The one test that is actually about the parser, the end to end
 * integration test, uses the real sample project instead.
 */
public final class TestProjects {

    public static final Path SAMPLE = Paths.get("sample-project", "sample.kdenlive");

    private TestProjects() {
    }

    /**
     * Two assets on two timebases, two clips on one video and one audio track, so that a
     * stored state is distinguishable from another by any single field.
     */
    public static Project small() {
        Rational film = new Rational(24000, 1001);
        Rational thirty = new Rational(30000, 1001);
        Resolution fullHd = new Resolution(1920, 1080);

        Asset a = new Asset("asset_001", "a.mp4", "/media/a.mp4", "hash-a",
                new TimeSpan(100, film), fullHd, "h264", "aac");
        Asset b = new Asset("asset_002", "b.mp4", "/media/b.mp4", "hash-b",
                new TimeSpan(200, thirty), fullHd, "h264", null);

        Clip first = Clip.of("clip_001", "asset_001", "track_001",
                new SourceRange(0, 49, 50, film), new TimeRange(0, 50));
        Clip second = Clip.of("clip_002", "asset_002", "track_002",
                new SourceRange(0, 99, 100, thirty), new TimeRange(50, 100));

        Timeline timeline = new Timeline("timeline_001", "Main", film, fullHd, 150,
                List.of(new Track("track_001", TrackType.VIDEO, List.of(first)),
                        new Track("track_002", TrackType.AUDIO, List.of(second))));
        return new Project("demo", fullHd, film, List.of(a, b), timeline);
    }

    /**
     * The same project with one field changed, so a test can tell two states apart.
     */
    public static Project altered() {
        Project base = small();
        Timeline timeline = new Timeline(base.timeline().id(), "Main", base.timeline().frameRate(),
                base.timeline().resolution(), 200, base.timeline().tracks());
        return new Project(base.name(), base.resolution(), base.frameRate(), base.assets(), timeline);
    }
}
