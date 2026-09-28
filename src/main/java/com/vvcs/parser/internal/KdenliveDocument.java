package com.vvcs.parser.internal;

import com.vvcs.model.Rational;
import com.vvcs.model.Resolution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory image of a parsed Kdenlive/MLT XML document.
 *
 * <p>This type is deliberately private to the parser. It mirrors the shape of the source
 * format (producers, playlists, tractors) and exists only long enough to be translated into
 * the editor-independent VVCS model, which is what callers ever see.
 */
public final class KdenliveDocument {

    private String rootDirectory;
    private String version;
    private Profile profile;
    private final Map<String, ProducerNode> producers = new LinkedHashMap<>();
    private final Map<String, PlaylistNode> playlists = new LinkedHashMap<>();
    private final Map<String, TractorNode> tractors = new LinkedHashMap<>();

    public String rootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public String version() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Profile profile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public Map<String, ProducerNode> producers() {
        return producers;
    }

    public Map<String, PlaylistNode> playlists() {
        return playlists;
    }

    public Map<String, TractorNode> tractors() {
        return tractors;
    }

    /** Resolves a chain or producer reference. Playlists and tractors are not producers. */
    public ProducerNode resolveProducer(String id) {
        return producers.get(id);
    }

    public PlaylistNode playlist(String id) {
        return playlists.get(id);
    }

    public TractorNode tractor(String id) {
        return tractors.get(id);
    }

    /** The project frame rate, taken from the profile. */
    public Rational frameRate() {
        return profile.frameRate();
    }

    public record Profile(
            String description,
            int width,
            int height,
            Rational frameRate,
            int sampleAspectNumerator,
            int sampleAspectDenominator) {
    }

    /** An MLT chain or producer: the unit that points at media or at generated frames. */
    public static final class ProducerNode {
        private final String id;
        private final String elementName;
        private final String in;
        private final String out;
        private final Map<String, String> properties;

        public ProducerNode(String id, String elementName, String in, String out,
                            Map<String, String> properties) {
            this.id = id;
            this.elementName = elementName;
            this.in = in;
            this.out = out;
            this.properties = properties;
        }
        public String id() {
            return id;
        }

        public String elementName() {
            return elementName;
        }

        public String in() {
            return in;
        }

        public String out() {
            return out;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public String property(String name) {
            return properties.get(name);
        }

        public String property(String name, String fallback) {
            return properties.getOrDefault(name, fallback);
        }
    }

    /** An ordered list of entries, which is how MLT expresses a single track's content. */
    public static final class PlaylistNode {
        private final String id;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final List<EntryNode> entries = new java.util.ArrayList<>();

        public PlaylistNode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public String property(String name) {
            return properties.get(name);
        }

        public void setProperty(String name, String value) {
            properties.put(name, value);
        }

        public List<EntryNode> entries() {
            return entries;
        }
    }

    /** A placement inside a playlist: a producer plus the range of it that is used. */
    public static final class EntryNode {
        private final String producerId;
        private final String in;
        private final String out;
        private final Map<String, String> properties = new LinkedHashMap<>();

        public EntryNode(String producerId, String in, String out) {
            this.producerId = producerId;
            this.in = in;
            this.out = out;
        }

        public String producerId() {
            return producerId;
        }

        public String in() {
            return in;
        }

        public String out() {
            return out;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public String property(String name) {
            return properties.get(name);
        }

        public void setProperty(String name, String value) {
            properties.put(name, value);
        }
    }

    /** A composite node: a container of tracks, which is what a sequence or a track is. */
    public static final class TractorNode {
        private final String id;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final List<TrackRef> tracks = new java.util.ArrayList<>();
        private final List<TransitionNode> transitions = new java.util.ArrayList<>();

        public TractorNode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public String property(String name) {
            return properties.get(name);
        }

        public void setProperty(String name, String value) {
            properties.put(name, value);
        }

        public List<TrackRef> tracks() {
            return tracks;
        }

        public List<TransitionNode> transitions() {
            return transitions;
        }
    }

    /** A reference from a tractor to one of its lanes. */
    public record TrackRef(String producerId, String hide) {
    }

    /** A compositing or mixing relationship between two lanes of a tractor. */
    public static final class TransitionNode {
        private final Map<String, String> properties = new LinkedHashMap<>();

        public TransitionNode() {
        }

        public Map<String, String> properties() {
            return properties;
        }

        public String service() {
            return properties.get("mlt_service");
        }

        /** Index of the lane this transition is attached to, or {@code -1} when unstated. */
        public int bTrack() {
            return intProperty("b_track", -1);
        }

        public int aTrack() {
            return intProperty("a_track", -1);
        }

        private int intProperty(String name, int fallback) {
            String value = properties.get(name);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
