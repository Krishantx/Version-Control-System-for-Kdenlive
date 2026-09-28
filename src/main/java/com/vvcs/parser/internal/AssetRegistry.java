package com.vvcs.parser.internal;

import com.vvcs.model.Asset;
import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;
import com.vvcs.model.TimeSpan;
import com.vvcs.parser.KdenliveParserException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Turns the media-referencing producers of a project into the project's set of assets.
 *
 * <p>Kdenlive writes one producer per clip in the project bin and another per placement on
 * the timeline, so the same file is named many times over. Assets are keyed by the media
 * hash the project records, falling back to the resolved path, so all of those references
 * collapse onto a single asset. Asset ids are assigned in first-appearance order, which
 * makes repeated runs over the same file produce identical ids.
 */
public final class AssetRegistry {

    private static final Logger LOG = Logger.getLogger(AssetRegistry.class.getName());

    private static final String HASH = "kdenlive:file_hash";
    private static final String RESOURCE = "resource";
    private static final String LENGTH = "length";
    private static final String SERVICE = "mlt_service";

    private static final Set<String> GENERATOR_SERVICES =
            Set.of("color", "noise", "qtext", "count", "tone", "blipflash", "pixbuf");
    private static final Set<String> GENERATOR_RESOURCES =
            Set.of("black", "black_track", "color", "noise", "count", "transparent", "");

    private final Map<String, Asset> byKey = new LinkedHashMap<>();
    private final Map<String, Asset> byProducerId = new LinkedHashMap<>();
    private final List<Asset> assets = new ArrayList<>();

    private AssetRegistry() {
    }

    public static AssetRegistry build(KdenliveDocument document, Path sourceFile) {
        AssetRegistry registry = new AssetRegistry();
        Rational projectFrameRate = document.frameRate();
        for (KdenliveDocument.ProducerNode producer : document.producers().values()) {
            if (!isFileBacked(producer)) {
                continue;
            }
            String key = identityKey(producer, document);
            Asset known = registry.byKey(key);
            if (known == null) {
                Asset created = toAsset(producer, document, projectFrameRate, sourceFile,
                        String.format("asset_%03d", registry.assets.size() + 1));
                registry.byKey.put(key, created);
                registry.assets.add(created);
                logAsset(created);
                known = created;
            }
            registry.byProducerId.put(producer.id(), known);
        }
        return registry;
    }

    private static void logAsset(Asset asset) {
        LOG.fine(() -> "asset " + asset.id() + " <- " + asset.name()
                + " (" + asset.frameRate() + ", " + asset.duration().frames() + " frames)");
    }

    public List<Asset> assets() {
        return List.copyOf(assets);
    }

    public Asset forProducer(String producerId) {
        Asset asset = byProducerId.get(producerId);
        if (asset == null) {
            throw new KdenliveParserException(KdenliveParserException.Reason.UNKNOWN_PRODUCER,
                    "producer '" + producerId + "' is not a known media reference");
        }
        return asset;
    }

    private Asset byKey(String key) {
        return byKey.get(key);
    }

    private static boolean isFileBacked(KdenliveDocument.ProducerNode producer) {
        String service = producer.property(SERVICE, "");
        String resource = producer.property(RESOURCE, "");
        if (resource.isBlank() || GENERATOR_RESOURCES.contains(resource.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (GENERATOR_SERVICES.contains(service)) {
            return false;
        }
        if (service.startsWith("frei0r.") || service.startsWith("ladspa.") || service.startsWith("qt")) {
            return false;
        }
        return true;
    }

    private static String identityKey(KdenliveDocument.ProducerNode producer, KdenliveDocument document) {
        String hash = producer.property(HASH, "").trim().toLowerCase(Locale.ROOT);
        if (!hash.isEmpty()) {
            return "hash:" + hash;
        }
        return "path:" + resolvePath(producer, document, null).toString();
    }

    private static Asset toAsset(KdenliveDocument.ProducerNode producer, KdenliveDocument document,
                                 Rational projectFrameRate, Path sourceFile, String id) {
        String resource = producer.property(RESOURCE, "");
        Path path = resolvePath(producer, document, sourceFile);
        Rational mediaFrameRate = mediaFrameRate(producer, projectFrameRate);
        long projectFrames = projectFrameCount(producer, projectFrameRate);
        long mediaFrames = projectFrameRate.scaleTo(projectFrames, mediaFrameRate);
        StreamInfo streams = StreamInfo.of(producer);

        return new Asset(
                id,
                path.getFileName() == null ? resource : path.getFileName().toString(),
                path.toString(),
                emptyToNull(producer.property(HASH)),
                new TimeSpan(mediaFrames, mediaFrameRate),
                streams.resolution(),
                emptyToNull(streams.videoCodec()),
                emptyToNull(streams.audioCodec()));
    }

    private static Path resolvePath(KdenliveDocument.ProducerNode producer, KdenliveDocument document,
                                    Path sourceFile) {
        String resource = producer.property(RESOURCE, "").trim();
        if (resource.contains("://")) {
            String scheme = resource.substring(0, resource.indexOf("://") + 3);
            throw new KdenliveParserException(KdenliveParserException.Reason.UNRESOLVED_RESOURCE,
                    "media resource '" + resource + "' uses the unsupported scheme " + scheme);
        }
        Path resourcePath = Path.of(resource);
        if (resourcePath.isAbsolute()) {
            return resourcePath.normalize();
        }
        String root = document.rootDirectory();
        if (root != null && !root.isBlank()) {
            return Path.of(root).resolve(resourcePath).normalize();
        }
        if (sourceFile != null) {
            Path parent = sourceFile.toAbsolutePath().getParent();
            if (parent != null) {
                return parent.resolve(resourcePath).normalize();
            }
        }
        throw new KdenliveParserException(KdenliveParserException.Reason.UNRESOLVED_RESOURCE,
                "cannot resolve media resource '" + resource
                        + "': the project declares no root directory");
    }

    private static Rational mediaFrameRate(KdenliveDocument.ProducerNode producer,
                                           Rational projectFrameRate) {
        String numerator = producer.property("meta.media.frame_rate_num", "").trim();
        String denominator = producer.property("meta.media.frame_rate_den", "").trim();
        if (numerator.isEmpty() || denominator.isEmpty()) {
            LOG.fine(() -> "producer " + producer.id() + " has no media frame rate;"
                    + " assuming the project frame rate");
            return projectFrameRate;
        }
        long parsedNumerator = Long.parseLong(numerator);
        long parsedDenominator = Long.parseLong(denominator);
        if (parsedNumerator <= 0 || parsedDenominator <= 0) {
            return projectFrameRate;
        }
        return new Rational(parsedNumerator, parsedDenominator);
    }

    private static long projectFrameCount(KdenliveDocument.ProducerNode producer,
                                          Rational projectFrameRate) {
        String length = producer.property(LENGTH, "").trim();
        if (length.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(length);
        } catch (NumberFormatException e) {
            return com.vvcs.parser.KdenliveTimeParser.parseFrames(length, projectFrameRate);
        }
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Container-level media facts recorded by Kdenlive, read per stream. */
    private record StreamInfo(Resolution resolution, String videoCodec, String audioCodec) {

        static StreamInfo of(KdenliveDocument.ProducerNode producer) {
            Resolution resolution = null;
            String videoCodec = null;
            String audioCodec = null;
            int streamCount = parseInt(producer.property("meta.media.nb_streams", "0"), 0);
            for (int index = 0; index < Math.max(streamCount, 1); index++) {
                String type = producer.property("meta.media." + index + ".stream.type", "");
                String codec = emptyToNull(producer.property("meta.media." + index + ".codec.name"));
                if ("video".equals(type) && videoCodec == null) {
                    videoCodec = codec;
                    resolution = streamResolution(producer, index);
                } else if ("audio".equals(type) && audioCodec == null) {
                    audioCodec = codec;
                }
            }
            if (resolution == null) {
                resolution = streamResolution(producer, 0);
            }
            return new StreamInfo(resolution, videoCodec, audioCodec);
        }

        private static Resolution streamResolution(KdenliveDocument.ProducerNode producer, int index) {
            int width = parseInt(producer.property("meta.media." + index + ".codec.width",
                    producer.property("meta.media.width", "0")), 0);
            int height = parseInt(producer.property("meta.media." + index + ".codec.height",
                    producer.property("meta.media.height", "0")), 0);
            return width > 0 && height > 0 ? new Resolution(width, height) : null;
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
