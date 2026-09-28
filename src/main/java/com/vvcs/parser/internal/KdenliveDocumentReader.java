package com.vvcs.parser.internal;

import com.vvcs.model.Rational;
import com.vvcs.parser.KdenliveParserException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;

/**
 * Reads a Kdenlive/MLT XML file into a {@link KdenliveDocument} using a streaming pull parser.
 *
 * <p>Streaming keeps memory flat for large projects and confines every XML-specific detail
 * to this class: nothing here knows what a clip, a track or an asset is.
 */
public final class KdenliveDocumentReader {

    private static final String ELEMENT_MLT = "mlt";
    private static final String ELEMENT_PROFILE = "profile";
    private static final String ELEMENT_PRODUCER = "producer";
    private static final String ELEMENT_CHAIN = "chain";
    private static final String ELEMENT_PLAYLIST = "playlist";
    private static final String ELEMENT_TRACTOR = "tractor";
    private static final String ELEMENT_ENTRY = "entry";
    private static final String ELEMENT_PROPERTY = "property";
    private static final String ELEMENT_BLANK = "blank";
    private static final String ELEMENT_TRACK = "track";
    private static final String ELEMENT_TRANSITION = "transition";

    public KdenliveDocument read(Path file) {
        if (file == null) {
            throw new KdenliveParserException(KdenliveParserException.Reason.FILE_NOT_FOUND,
                    "no input file given");
        }
        if (!Files.isRegularFile(file)) {
            throw new KdenliveParserException(KdenliveParserException.Reason.FILE_NOT_FOUND,
                    "Kdenlive project not found: " + file.toAbsolutePath());
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return read(in, file);
        } catch (IOException e) {
            throw new KdenliveParserException(KdenliveParserException.Reason.FILE_NOT_FOUND,
                    "cannot read Kdenlive project: " + file.toAbsolutePath(), e);
        }
    }

    KdenliveDocument read(InputStream in, Path source) {
        XMLStreamReader reader = null;
        try {
            reader = createFactory().createXMLStreamReader(in);
            KdenliveDocument document = new KdenliveDocument();
            Deque<Object> stack = new ArrayDeque<>();
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> stack.push(startElement(reader, document, stack));
                    case XMLStreamConstants.CHARACTERS -> appendText(stack.peek(), reader.getText());
                    case XMLStreamConstants.CDATA -> appendText(stack.peek(), reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> endElement(stack);
                    default -> {
                    }
                }
            }
            if (document.profile() == null) {
                throw new KdenliveParserException(KdenliveParserException.Reason.MISSING_PROFILE,
                        "no <profile> element in " + source.getFileName()
                                + "; without it the project timebase is unknown");
            }
            return document;
        } catch (XMLStreamException e) {
            throw new KdenliveParserException(KdenliveParserException.Reason.MALFORMED_XML,
                    "malformed Kdenlive XML in " + source.getFileName() + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }
    }

    private Object startElement(XMLStreamReader reader, KdenliveDocument document,
                                Deque<Object> stack) {
        return switch (reader.getLocalName()) {
            case ELEMENT_MLT -> {
                document.setRootDirectory(attribute(reader, "root", null));
                document.setVersion(attribute(reader, "version", null));
                yield new Marker(ELEMENT_MLT);
            }
            case ELEMENT_PROFILE -> {
                document.setProfile(readProfile(reader));
                yield new Marker(ELEMENT_PROFILE);
            }
            case ELEMENT_CHAIN, ELEMENT_PRODUCER -> {
                KdenliveDocument.ProducerNode node = new KdenliveDocument.ProducerNode(
                        attribute(reader, "id", null),
                        reader.getLocalName(),
                        attribute(reader, "in", null),
                        attribute(reader, "out", null),
                        new LinkedHashMap<>());
                document.producers().put(node.id(), node);
                yield node;
            }
            case ELEMENT_PLAYLIST -> {
                KdenliveDocument.PlaylistNode node =
                        new KdenliveDocument.PlaylistNode(attribute(reader, "id", null));
                document.playlists().put(node.id(), node);
                yield node;
            }
            case ELEMENT_TRACTOR -> {
                KdenliveDocument.TractorNode node =
                        new KdenliveDocument.TractorNode(attribute(reader, "id", null));
                document.tractors().put(node.id(), node);
                yield node;
            }
            case ELEMENT_ENTRY -> {
                KdenliveDocument.PlaylistNode playlist = asOwner(stack.peek(), KdenliveDocument.PlaylistNode.class);
                if (playlist == null) {
                    throw new KdenliveParserException(KdenliveParserException.Reason.MALFORMED_XML,
                            "<entry> found outside a <playlist>");
                }
                KdenliveDocument.EntryNode entry = new KdenliveDocument.EntryNode(
                        attribute(reader, "producer", null),
                        attribute(reader, "in", null),
                        attribute(reader, "out", null));
                playlist.entries().add(entry);
                yield entry;
            }
            case ELEMENT_TRACK -> {
                KdenliveDocument.TractorNode tractor = asOwner(stack.peek(), KdenliveDocument.TractorNode.class);
                if (tractor != null) {
                    tractor.tracks().add(new KdenliveDocument.TrackRef(
                            attribute(reader, "producer", null),
                            attribute(reader, "hide", null)));
                }
                yield new Marker(ELEMENT_TRACK);
            }
            case ELEMENT_TRANSITION -> {
                KdenliveDocument.TractorNode tractor = asOwner(stack.peek(), KdenliveDocument.TractorNode.class);
                KdenliveDocument.TransitionNode transition = new KdenliveDocument.TransitionNode();
                if (tractor != null) {
                    tractor.transitions().add(transition);
                }
                yield transition;
            }
            case ELEMENT_PROPERTY -> new PropertyValue(attribute(reader, "name", null));
            case ELEMENT_BLANK -> throw new KdenliveParserException(
                    KdenliveParserException.Reason.UNSUPPORTED_TIMELINE,
                    "timeline gap (<blank>) found; gaps are not modelled yet and importing one"
                            + " would silently shift every following clip");
            default -> new Marker(reader.getLocalName());
        };
    }

    private void endElement(Deque<Object> stack) {
        Object closed = stack.pop();
        if (!(closed instanceof PropertyValue property)) {
            return;
        }
        Object owner = stack.peek();
        String value = property.text();
        if (owner instanceof KdenliveDocument.ProducerNode node) {
            node.properties().put(property.key(), value);
        } else if (owner instanceof KdenliveDocument.PlaylistNode node) {
            node.setProperty(property.key(), value);
        } else if (owner instanceof KdenliveDocument.TractorNode node) {
            node.setProperty(property.key(), value);
        } else if (owner instanceof KdenliveDocument.EntryNode node) {
            node.setProperty(property.key(), value);
        } else if (owner instanceof KdenliveDocument.TransitionNode node) {
            node.properties().put(property.key(), value);
        }
    }

    private KdenliveDocument.Profile readProfile(XMLStreamReader reader) {
        long numerator = requiredLong(reader, "frame_rate_num");
        long denominator = requiredLong(reader, "frame_rate_den");
        if (numerator <= 0 || denominator <= 0) {
            throw new KdenliveParserException(KdenliveParserException.Reason.MISSING_PROFILE,
                    "profile declares an invalid frame rate " + numerator + "/" + denominator);
        }
        return new KdenliveDocument.Profile(
                attribute(reader, "description", null),
                (int) requiredLong(reader, "width"),
                (int) requiredLong(reader, "height"),
                new Rational(numerator, denominator),
                (int) optionalLong(reader, "sample_aspect_num", 1L),
                (int) optionalLong(reader, "sample_aspect_den", 1L));
    }

    private static void appendText(Object context, String text) {
        if (context instanceof PropertyValue property) {
            property.append(text);
        }
    }

    private static <T> T asOwner(Object candidate, Class<T> type) {
        return type.isInstance(candidate) ? type.cast(candidate) : null;
    }

    private static String attribute(XMLStreamReader reader, String name, String fallback) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? fallback : value;
    }

    private static long requiredLong(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null || value.isBlank()) {
            throw new KdenliveParserException(KdenliveParserException.Reason.MISSING_PROFILE,
                    "profile is missing required attribute '" + name + "'");
        }
        return Long.parseLong(value.trim());
    }

    private static long optionalLong(XMLStreamReader reader, String name, long fallback) {
        String value = reader.getAttributeValue(null, name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return factory;
    }

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
        }
    }

    /** An element VVCS does not need; also blocks properties from leaking to its parent. */
    private record Marker(String name) {
    }

    /** A {@code <property>} element collecting its text content. */
    private static final class PropertyValue {
        private final String key;
        private final StringBuilder value = new StringBuilder();

        private PropertyValue(String key) {
            this.key = key;
        }

        private void append(String text) {
            value.append(text);
        }

        private String key() {
            return key;
        }

        private String text() {
            return value.toString().trim();
        }
    }
}
