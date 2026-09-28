package com.vvcs.parser.internal;

import com.vvcs.model.Asset;
import com.vvcs.model.Clip;
import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;
import com.vvcs.model.SourceRange;
import com.vvcs.model.TimeRange;
import com.vvcs.model.Timeline;
import com.vvcs.model.Track;
import com.vvcs.model.TrackType;
import com.vvcs.parser.KdenliveParserException;
import com.vvcs.parser.KdenliveTimeParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Builds the VVCS timeline from the main sequence of a parsed Kdenlive project.
 *
 * <p>MLT has no concept of a timeline position on an entry. A lane is a playlist whose
 * entries are concatenated, and positions follow from the order in which they appear. This
 * builder therefore walks each lane once, accumulating lengths, which is why every position
 * is an exact integer number of project frames rather than an accumulated float.
 *
 * <p>A Kdenlive lane is itself a tractor holding one playlist per content slot plus an
 * auxiliary slot; all slots are walked in order so that nothing an editor placed is lost.
 */
public final class TimelineBuilder {

    private static final Logger LOG = Logger.getLogger(TimelineBuilder.class.getName());

    private static final String AUDIO_TRACK = "kdenlive:audio_track";
    private static final String MAX_DURATION = "kdenlive:maxduration";
    private static final String SOURCE_ID = "kdenlive:id";

    private static final Set<String> AUDIO_TRANSITIONS = Set.of("mix", "volume", "panner", "audiolevel");
    private static final Set<String> VIDEO_TRANSITIONS =
            Set.of("qtblend", "composite", "frei0r.cairoblend", "luma", "affine", "qtblend.transform");
    private static final Set<String> BACKGROUND_SERVICES = Set.of("color", "noise");
    private static final Set<String> BACKGROUND_RESOURCES = Set.of("black", "black_track");

    private final KdenliveDocument document;
    private final AssetRegistry assets;
    private final Rational frameRate;
    private final String timelineId;

    private int clipIndex = 0;

    public TimelineBuilder(KdenliveDocument document, AssetRegistry assets, String timelineId) {
        this.document = document;
        this.assets = assets;
        this.frameRate = document.frameRate();
        this.timelineId = timelineId;
    }

    public Timeline build(KdenliveDocument.TractorNode sequence, String sequenceName) {
        List<Track> tracks = new ArrayList<>();
        Map<Integer, TrackType> typesFromTransitions = typesFromTransitions(sequence);
        int index = 0;
        for (KdenliveDocument.TrackRef lane : sequence.tracks()) {
            index++;
            if (isBackgroundLane(lane.producerId())) {
                LOG.fine(() -> "skipping background lane " + lane.producerId());
                continue;
            }
            KdenliveDocument.TractorNode laneNode = document.tractor(lane.producerId());
            if (laneNode == null) {
                throw new KdenliveParserException(KdenliveParserException.Reason.UNKNOWN_PRODUCER,
                        "sequence lane " + index + " references unknown producer '"
                                + lane.producerId() + "'");
            }
            String trackId = String.format("track_%03d", tracks.size() + 1);
            TrackType type = trackType(laneNode, lane, typesFromTransitions.get(index));
            if (type == TrackType.UNKNOWN) {
                int laneIndex = index;
                LOG.warning(() -> "lane " + laneIndex + " of the sequence carries no audio or video"
                        + " information; its type is reported as UNKNOWN");
            }
            tracks.add(new Track(trackId, type, clipsOf(laneNode, trackId)));
        }
        if (tracks.isEmpty()) {
            throw new KdenliveParserException(KdenliveParserException.Reason.SEQUENCE_NOT_FOUND,
                    "the main sequence contains no usable tracks");
        }
        long duration = tracks.stream().mapToLong(Track::endFrame).max().orElse(0L);
        warnOnDurationMismatch(sequence, duration);
        KdenliveDocument.Profile profile = document.profile();
        return new Timeline(timelineId, sequenceName, frameRate,
                new Resolution(profile.width(), profile.height()), duration, tracks);
    }

    private List<Clip> clipsOf(KdenliveDocument.TractorNode lane, String trackId) {
        List<Clip> clips = new ArrayList<>();
        long position = 0;
        for (KdenliveDocument.TrackRef slot : lane.tracks()) {
            KdenliveDocument.PlaylistNode playlist = document.playlist(slot.producerId());
            if (playlist == null) {
                throw new KdenliveParserException(KdenliveParserException.Reason.UNKNOWN_PRODUCER,
                        "lane " + lane.id() + " references unknown playlist '"
                                + slot.producerId() + "'");
            }
            for (KdenliveDocument.EntryNode entry : playlist.entries()) {
                Clip clip = clip(entry, trackId, position);
                clips.add(clip);
                position = clip.timeline().end();
            }
        }
        return clips;
    }

    private Clip clip(KdenliveDocument.EntryNode entry, String trackId, long position) {
        KdenliveDocument.ProducerNode producer = document.resolveProducer(entry.producerId());
        if (producer == null) {
            throw new KdenliveParserException(KdenliveParserException.Reason.UNKNOWN_PRODUCER,
                    "timeline entry references unknown producer '" + entry.producerId() + "'");
        }
        Asset asset = assets.forProducer(entry.producerId());

        long inFrame = KdenliveTimeParser.parseFrames(entry.in(), frameRate);
        long outFrame = KdenliveTimeParser.parseFrames(entry.out(), frameRate);
        if (outFrame < inFrame) {
            throw new KdenliveParserException(KdenliveParserException.Reason.MALFORMED_XML,
                    "entry of '" + entry.producerId() + "' ends (" + outFrame
                            + ") before it starts (" + inFrame + ")");
        }
        long duration = outFrame - inFrame + 1;

        Rational mediaFrameRate = asset.frameRate();
        long sourceIn = frameRate.scaleTo(inFrame, mediaFrameRate);
        long sourceOut = frameRate.scaleTo(outFrame, mediaFrameRate);
        SourceRange source = new SourceRange(
                sourceIn,
                Math.max(sourceIn, sourceOut),
                sourceOut - sourceIn + 1,
                mediaFrameRate);

        clipIndex++;
        String clipId = String.format("clip_%03d", clipIndex);
        // The editor's own identifier for the entry, carried through unchanged. It is what
        // lets a later parse recognise this clip after it has been moved or trimmed, and the
        // diff engine treats it as the primary identity of an occurrence.
        String sourceId = entry.property(SOURCE_ID);
        return new Clip(clipId, asset.id(), trackId, source, new TimeRange(position, duration),
                sourceId);
    }

    private boolean isBackgroundLane(String producerId) {
        KdenliveDocument.ProducerNode node = document.resolveProducer(producerId);
        if (node == null) {
            return false;
        }
        String service = node.property("mlt_service", "").toLowerCase(Locale.ROOT);
        String resource = node.property("resource", "").toLowerCase(Locale.ROOT);
        return BACKGROUND_SERVICES.contains(service) || BACKGROUND_RESOURCES.contains(resource);
    }

    private Map<Integer, TrackType> typesFromTransitions(KdenliveDocument.TractorNode sequence) {
        Map<Integer, TrackType> types = new LinkedHashMap<>();
        for (KdenliveDocument.TransitionNode transition : sequence.transitions()) {
            TrackType type = typeOfTransition(transition.service());
            if (type == null || transition.bTrack() < 0) {
                continue;
            }
            types.putIfAbsent(transition.bTrack(), type);
        }
        return types;
    }

    private static TrackType typeOfTransition(String service) {
        if (service == null) {
            return null;
        }
        String name = service.toLowerCase(Locale.ROOT);
        if (AUDIO_TRANSITIONS.contains(name)) {
            return TrackType.AUDIO;
        }
        return VIDEO_TRANSITIONS.contains(name) ? TrackType.VIDEO : null;
    }

    private TrackType trackType(KdenliveDocument.TractorNode lane, KdenliveDocument.TrackRef reference,
                                TrackType fromTransition) {
        List<KdenliveDocument.PlaylistNode> slots = slotPlaylists(lane);
        for (KdenliveDocument.PlaylistNode slot : slots) {
            if (isTrue(slot.property(AUDIO_TRACK))) {
                return TrackType.AUDIO;
            }
        }
        if (isTrue(lane.property(AUDIO_TRACK))) {
            return TrackType.AUDIO;
        }
        TrackType fromHide = typeOfHide(hideOfLane(lane, reference));
        if (fromHide != null) {
            return fromHide;
        }
        return fromTransition != null ? fromTransition : TrackType.UNKNOWN;
    }

    /**
     * Kdenlive states which half of a lane is hidden on the sequence's reference to the lane
     * or, when that is unstated, on the lane's own content slots.
     */
    private String hideOfLane(KdenliveDocument.TractorNode lane, KdenliveDocument.TrackRef reference) {
        if (reference != null && reference.hide() != null && !reference.hide().isBlank()) {
            return reference.hide();
        }
        for (KdenliveDocument.TrackRef slot : lane.tracks()) {
            if (slot.hide() != null && !slot.hide().isBlank()) {
                return slot.hide();
            }
        }
        return null;
    }

    private static TrackType typeOfHide(String hide) {
        if (hide == null) {
            return null;
        }
        return switch (hide.trim().toLowerCase(Locale.ROOT)) {
            case "video", "v" -> TrackType.AUDIO;
            case "audio", "a", "both" -> TrackType.VIDEO;
            default -> null;
        };
    }

    private List<KdenliveDocument.PlaylistNode> slotPlaylists(KdenliveDocument.TractorNode lane) {
        List<KdenliveDocument.PlaylistNode> playlists = new ArrayList<>();
        for (KdenliveDocument.TrackRef slot : lane.tracks()) {
            KdenliveDocument.PlaylistNode playlist = document.playlist(slot.producerId());
            if (playlist != null) {
                playlists.add(playlist);
            }
        }
        return playlists;
    }

    private void warnOnDurationMismatch(KdenliveDocument.TractorNode sequence, long duration) {
        String reported = sequence.property(MAX_DURATION);
        if (reported == null || reported.isBlank()) {
            return;
        }
        long reportedFrames;
        try {
            reportedFrames = Long.parseLong(reported.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (Math.abs(reportedFrames - duration) > 1) {
            LOG.warning(() -> "sequence '" + sequence.id() + "' reports a duration of "
                    + reportedFrames + " frames but its clips add up to " + duration);
        }
    }

    private static boolean isTrue(String value) {
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim()));
    }
}
