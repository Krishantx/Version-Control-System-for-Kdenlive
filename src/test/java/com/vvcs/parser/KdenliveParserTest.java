package com.vvcs.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Project;
import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;
import com.vvcs.model.Timeline;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Kdenlive adapter against a real Kdenlive project.
 *
 * <p>The expectations are the project's own facts: the profile it declares, the media files
 * it references and the positions its clips add up to. The media frame counts below were
 * cross-checked against the files themselves.
 */
class KdenliveParserTest {

    private static final Path PROJECT_FILE = Paths.get("sample-project", "sample.kdenlive");
    private static final Path MEDIA_ROOT = PROJECT_FILE.toAbsolutePath().getParent();

    private static final Rational FILM = new Rational(24000, 1001);
    private static final Rational NTSC_30 = new Rational(30000, 1001);
    private static final Rational NTSC_60 = new Rational(60000, 1001);
    private static final Rational TWENTY_FIVE = new Rational(25, 1);

    private static Project project;

    @BeforeAll
    static void parseTheProject() {
        assertTrue(Files.isRegularFile(PROJECT_FILE),
                "test input is missing: " + PROJECT_FILE.toAbsolutePath());
        project = new KdenliveParser().parse(PROJECT_FILE);
    }

    @Test
    void readsTheProjectProfile() {
        assertAll(
                () -> assertEquals("sample", project.name()),
                () -> assertEquals(new Resolution(3840, 2160), project.resolution()),
                () -> assertEquals(FILM, project.frameRate()));
    }

    @Test
    void collapsesNineProducerReferencesIntoFourAssets() {
        assertAll(
                () -> assertEquals(4, project.assetCount()),
                () -> assertEquals(List.of("asset_001", "asset_002", "asset_003", "asset_004"),
                        project.assets().stream().map(Asset::id).toList()),
                () -> assertEquals(List.of(
                                "10225775-hd_1920_1080_30fps.mp4",
                                "12014302_1920_1080_60fps.mp4",
                                "16817949_3840_2160_25fps.mp4",
                                "16852366_3840_2160_24fps.mp4"),
                        project.assets().stream().map(Asset::name).toList()));
    }

    @Test
    void keepsTheMediaHashOfEveryAsset() {
        assertAll(
                () -> assertEquals("3f0cfd5f6b381db782bde5fa598c26b5", asset("asset_001").hash()),
                () -> assertEquals("824c5225b66dd976a3abddb996cbc6cf", asset("asset_002").hash()),
                () -> assertEquals("77d71cfaf57b69203e315c16b3bee9b1", asset("asset_003").hash()),
                () -> assertEquals("cb74600a9bdd853a6360fcecd9568a43", asset("asset_004").hash()));
    }

    @Test
    void resolvesAssetPathsAgainstTheProjectRoot() {
        assertAll(
                () -> assertEquals(mediaFile("10225775-hd_1920_1080_30fps.mp4"),
                        Paths.get(asset("asset_001").path())),
                () -> assertEquals(mediaFile("16852366_3840_2160_24fps.mp4"),
                        Paths.get(asset("asset_004").path())));
    }

    @Test
    void keepsEachAssetInItsOwnMediaTimebase() {
        assertAll(
                () -> assertEquals(NTSC_30, asset("asset_001").frameRate()),
                () -> assertEquals(NTSC_60, asset("asset_002").frameRate()),
                () -> assertEquals(TWENTY_FIVE, asset("asset_003").frameRate()),
                () -> assertEquals(FILM, asset("asset_004").frameRate()));
    }

    @Test
    void reportsMediaLengthInMediaFrames() {
        assertAll(
                () -> assertEquals(1005L, asset("asset_001").duration().frames()),
                () -> assertEquals(643L, asset("asset_002").duration().frames()),
                () -> assertEquals(780L, asset("asset_003").duration().frames()),
                () -> assertEquals(1091L, asset("asset_004").duration().frames()));
    }

    @Test
    void keepsTheMediaFormatOfEveryAsset() {
        assertAll(
                () -> assertEquals(new Resolution(1920, 1080), asset("asset_001").resolution()),
                () -> assertEquals(new Resolution(3840, 2160), asset("asset_003").resolution()),
                () -> assertEquals("h264", asset("asset_001").videoCodec()),
                () -> assertEquals("aac", asset("asset_001").audioCodec()),
                () -> assertNull(asset("asset_002").audioCodec(),
                        "12014302_1920_1080_60fps.mp4 has no audio stream"));
    }

    @Test
    void givesEveryTimelinePlacementItsOwnClip() {
        Timeline timeline = project.timeline();
        List<Clip> clips = timeline.clips();
        assertAll(
                () -> assertEquals(5, clips.size()),
                () -> assertEquals(5, clips.stream().map(Clip::id).distinct().count(),
                        "each placement has its own identity, even for repeated media"),
                () -> assertEquals(2, clips.stream()
                        .filter(c -> c.assetId().equals("asset_001")).count(),
                        "asset_001 is placed twice, once on the audio track and once on video"),
                () -> assertNotEquals(clip("clip_001").id(), clip("clip_002").id()));
    }

    @Test
    void keepsSourceRangesInTheTimebaseOfTheClipsOwnAsset() {
        assertAll(
                () -> assertClip(clip("clip_001"), "asset_001", 0, 1004, 1005, NTSC_30),
                () -> assertClip(clip("clip_002"), "asset_001", 0, 1004, 1005, NTSC_30),
                () -> assertClip(clip("clip_003"), "asset_002", 0, 640, 641, NTSC_60),
                () -> assertClip(clip("clip_004"), "asset_003", 0, 779, 780, TWENTY_FIVE),
                () -> assertClip(clip("clip_005"), "asset_004", 0, 1090, 1091, FILM));
    }

    @Test
    void laysOutTimelinePositionsInProjectFrames() {
        assertAll(
                () -> assertPlacement(clip("clip_001"), 0, 804),
                () -> assertPlacement(clip("clip_002"), 0, 804),
                () -> assertPlacement(clip("clip_003"), 804, 257),
                () -> assertPlacement(clip("clip_004"), 1061, 748),
                () -> assertPlacement(clip("clip_005"), 1809, 1091));
    }

    @Test
    void linksEveryClipToTheTrackItSitsOn() {
        assertAll(
                () -> assertEquals("track_001", clip("clip_001").trackId()),
                () -> assertEquals("track_004", clip("clip_002").trackId()),
                () -> assertEquals("track_004", clip("clip_005").trackId()));
    }

    @Test
    void keepsTheSequenceTracksInOrderWithTheirType() {
        List<Track> tracks = project.timeline().tracks();
        assertAll(
                () -> assertEquals(4, tracks.size()),
                () -> assertEquals(List.of("track_001", "track_002", "track_003", "track_004"),
                        tracks.stream().map(Track::id).toList()),
                () -> assertEquals(
                        List.of(TrackType.AUDIO, TrackType.AUDIO, TrackType.VIDEO, TrackType.VIDEO),
                        tracks.stream().map(Track::type).toList()),
                () -> assertEquals(List.of(1, 0, 0, 4),
                        tracks.stream().map(Track::clipCount).toList()));
    }

    @Test
    void keepsEmptyLanesAndDropsTheBackgroundLane() {
        assertAll(
                () -> assertTrue(project.timeline().track("track_002").isEmpty()),
                () -> assertTrue(project.timeline().track("track_003").isEmpty()),
                () -> assertEquals(4, project.timeline().tracks().size(),
                        "Kdenlive's black background lane is not a track an editor placed"),
                () -> assertTrue(project.timeline().clips().stream()
                        .noneMatch(c -> c.assetId() == null)));
    }

    @Test
    void timesTheTimelineAgainstTheProjectProfile() {
        Timeline timeline = project.timeline();
        assertAll(
                () -> assertEquals("timeline_001", timeline.id()),
                () -> assertEquals(FILM, timeline.frameRate()),
                () -> assertEquals(new Resolution(3840, 2160), timeline.resolution()),
                () -> assertEquals(2900L, timeline.duration(),
                        "804 + 257 + 748 + 1091 frames of video, as the project itself reports"));
    }

    @Test
    void ignoresTheProjectBinRatherThanImportingItAsATimeline() {
        assertEquals(5, project.timeline().clipCount(),
                "the project bin lists the same media again and must not become a second timeline");
    }

    @Test
    void parsingTwiceProducesTheSameResult() throws IOException {
        Project first = new KdenliveParser().parse(PROJECT_FILE);
        Project second = new KdenliveParser().parse(PROJECT_FILE);
        assertEquals(toJson(first), toJson(second));
    }

    @Test
    void generatesIdentifiersIndependentlyOfTheSourceProject() {
        assertAll(
                () -> assertTrue(clip("clip_002").id().startsWith("clip_")),
                () -> assertNotEquals("4", clip("clip_002").id(),
                        "the kdenlive:id of a source clip is reused for several placements"),
                () -> assertEquals("clip_001", project.timeline().clips().get(0).id()));
    }

    @Test
    void reportsAMissingProjectFile() {
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(Paths.get("does-not-exist.kdenlive")));
        assertEquals(KdenliveParserException.Reason.FILE_NOT_FOUND, failure.reason());
    }

    @Test
    void reportsMalformedXml(@TempDir Path directory) throws IOException {
        Path broken = write(directory, "broken.kdenlive", "<?xml version='1.0'?><mlt><profile ");
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(broken));
        assertEquals(KdenliveParserException.Reason.MALFORMED_XML, failure.reason());
    }

    @Test
    void reportsAProjectWithoutAProfile(@TempDir Path directory) throws IOException {
        Path file = write(directory, "noprofile.kdenlive",
                "<mlt version='7.40.0'><chain id='chain0' out='00:00:01.000'/></mlt>");
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(file));
        assertEquals(KdenliveParserException.Reason.MISSING_PROFILE, failure.reason());
    }

    @Test
    void reportsAProjectWithoutAMainSequence(@TempDir Path directory) throws IOException {
        Path file = write(directory, "nosequence.kdenlive", """
                <?xml version='1.0' encoding='utf-8'?>
                <mlt version="7.40.0" root="/tmp">
                 <profile width="1920" height="1080" frame_rate_num="25" frame_rate_den="1"/>
                 <chain id="chain0" out="00:00:04.000">
                  <property name="length">100</property>
                  <property name="resource">clip.mp4</property>
                  <property name="mlt_service">avformat-novalidate</property>
                 </chain>
                </mlt>
                """);
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(file));
        assertEquals(KdenliveParserException.Reason.SEQUENCE_NOT_FOUND, failure.reason());
    }

    @Test
    void reportsAnEntryThatReferencesAnUnknownProducer(@TempDir Path directory) throws IOException {
        Path file = write(directory, "dangling.kdenlive", """
                <?xml version='1.0' encoding='utf-8'?>
                <mlt version="7.40.0" root="/tmp">
                 <profile width="1920" height="1080" frame_rate_num="25" frame_rate_den="1"/>
                 <playlist id="playlist0">
                  <entry in="00:00:00.000" out="00:00:01.960" producer="chain-missing"/>
                 </playlist>
                 <tractor id="tractor0" out="00:00:01.960">
                  <property name="kdenlive:audio_track">1</property>
                  <track hide="video" producer="playlist0"/>
                 </tractor>
                 <tractor id="{sequence}" out="00:00:01.960">
                  <property name="kdenlive:sequenceproperties.documentuuid">{sequence}</property>
                  <track producer="tractor0"/>
                 </tractor>
                </mlt>
                """);
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(file));
        assertEquals(KdenliveParserException.Reason.UNKNOWN_PRODUCER, failure.reason());
        assertTrue(failure.getMessage().contains("chain-missing"));
    }

    @Test
    void reportsMediaThatCannotBeResolved(@TempDir Path directory) throws IOException {
        Path file = write(directory, "noroot.kdenlive", """
                <?xml version='1.0' encoding='utf-8'?>
                <mlt version="7.40.0">
                 <profile width="1920" height="1080" frame_rate_num="25" frame_rate_den="1"/>
                 <chain id="chain0" out="00:00:04.000">
                  <property name="length">100</property>
                  <property name="resource">clip.mp4</property>
                  <property name="mlt_service">avformat-novalidate</property>
                 </chain>
                 <playlist id="playlist0">
                  <entry in="00:00:00.000" out="00:00:01.960" producer="chain0"/>
                 </playlist>
                 <tractor id="tractor0" out="00:00:01.960">
                  <track hide="audio" producer="playlist0"/>
                 </tractor>
                 <tractor id="{sequence}" out="00:00:01.960">
                  <property name="kdenlive:sequenceproperties.documentuuid">{sequence}</property>
                  <track producer="tractor0"/>
                 </tractor>
                </mlt>
                """);
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(file));
        assertEquals(KdenliveParserException.Reason.UNRESOLVED_RESOURCE, failure.reason());
    }

    @Test
    void refusesToImportATimelineThatContainsGaps(@TempDir Path directory) throws IOException {
        Path file = write(directory, "gap.kdenlive", """
                <?xml version='1.0' encoding='utf-8'?>
                <mlt version="7.40.0" root="/tmp">
                 <profile width="1920" height="1080" frame_rate_num="25" frame_rate_den="1"/>
                 <chain id="chain0" out="00:00:04.000">
                  <property name="length">100</property>
                  <property name="resource">clip.mp4</property>
                  <property name="mlt_service">avformat-novalidate</property>
                 </chain>
                 <playlist id="playlist0">
                  <entry in="00:00:00.000" out="00:00:01.960" producer="chain0"/>
                  <blank length="25"/>
                 </playlist>
                 <tractor id="tractor0" out="00:00:03.960">
                  <track hide="audio" producer="playlist0"/>
                 </tractor>
                 <tractor id="{sequence}" out="00:00:03.960">
                  <property name="kdenlive:sequenceproperties.documentuuid">{sequence}</property>
                  <track producer="tractor0"/>
                 </tractor>
                </mlt>
                """);
        KdenliveParserException failure = assertThrows(KdenliveParserException.class,
                () -> new KdenliveParser().parse(file));
        assertEquals(KdenliveParserException.Reason.UNSUPPORTED_TIMELINE, failure.reason());
    }

    @Test
    void importsASingleTrackSequenceFromASmallProject(@TempDir Path directory) throws IOException {
        Path file = write(directory, "simple.kdenlive", """
                <?xml version='1.0' encoding='utf-8'?>
                <mlt version="7.40.0" root="/media">
                 <profile width="1920" height="1080" frame_rate_num="25" frame_rate_den="1"/>
                 <chain id="chain0" out="00:00:04.000">
                  <property name="length">100</property>
                  <property name="resource">clip.mp4</property>
                  <property name="mlt_service">avformat-novalidate</property>
                  <property name="meta.media.frame_rate_num">50</property>
                  <property name="meta.media.frame_rate_den">1</property>
                  <property name="meta.media.nb_streams">1</property>
                  <property name="meta.media.0.stream.type">video</property>
                  <property name="meta.media.0.codec.name">h264</property>
                  <property name="meta.media.0.codec.width">1920</property>
                  <property name="meta.media.0.codec.height">1080</property>
                 </chain>
                 <playlist id="playlist0">
                  <entry in="00:00:00.000" out="00:00:01.960" producer="chain0"/>
                 </playlist>
                 <tractor id="tractor0" out="00:00:01.960">
                  <track hide="audio" producer="playlist0"/>
                 </tractor>
                 <tractor id="{sequence}" out="00:00:01.960">
                  <property name="kdenlive:clipname">Intro</property>
                  <property name="kdenlive:sequenceproperties.documentuuid">{sequence}</property>
                  <track producer="tractor0"/>
                 </tractor>
                 <tractor id="wrapper" out="00:00:01.960">
                  <property name="kdenlive:projectTractor">1</property>
                  <track producer="{sequence}"/>
                 </tractor>
                </mlt>
                """);
        Project parsed = new KdenliveParser().parse(file);
        Clip clip = parsed.timeline().clips().get(0);
        assertAll(
                () -> assertEquals("Intro", parsed.timeline().name()),
                () -> assertEquals(1, parsed.timeline().tracks().size()),
                () -> assertEquals(TrackType.VIDEO, parsed.timeline().tracks().get(0).type()),
                () -> assertEquals(0L, clip.source().inFrame()),
                () -> assertEquals(98L, clip.source().outFrame(),
                        "50 project frames at 25 fps end on media frame 98 at 50 fps"),
                () -> assertEquals(new Rational(50, 1), clip.source().timebase()),
                () -> assertEquals(0L, clip.timeline().start()),
                () -> assertEquals(50L, clip.timeline().duration(),
                        "00:00:01.960 at 25 fps is 50 project frames"),
                () -> assertEquals(200L, parsed.assets().get(0).duration().frames(),
                        "100 project frames of 25 fps material is 200 media frames of 50 fps"),
                () -> assertNull(parsed.assets().get(0).audioCodec()));
    }

    private static Asset asset(String id) {
        return project.asset(id);
    }

    private static Clip clip(String id) {
        return project.timeline().clips().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clip " + id));
    }

    private static void assertClip(Clip clip, String assetId, long in, long out, long duration,
                                   Rational timebase) {
        assertAll(
                () -> assertEquals(assetId, clip.assetId(), clip.id() + " asset"),
                () -> assertEquals(in, clip.source().inFrame(), clip.id() + " source in"),
                () -> assertEquals(out, clip.source().outFrame(), clip.id() + " source out"),
                () -> assertEquals(duration, clip.source().duration(), clip.id() + " source duration"),
                () -> assertEquals(timebase, clip.source().timebase(), clip.id() + " source timebase"));
    }

    private static void assertPlacement(Clip clip, long start, long duration) {
        assertAll(
                () -> assertEquals(start, clip.timeline().start(), clip.id() + " start"),
                () -> assertEquals(duration, clip.timeline().duration(), clip.id() + " duration"));
    }

    private static Path mediaFile(String name) {
        return MEDIA_ROOT.resolve(name);
    }

    private static Path write(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String toJson(Project value) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(value);
    }
}
