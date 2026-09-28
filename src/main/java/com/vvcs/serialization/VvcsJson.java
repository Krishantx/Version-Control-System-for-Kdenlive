package com.vvcs.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The two JSON shapes VVCS persists, and the only place an {@code ObjectMapper} is configured.
 *
 * <p>They are deliberately different and must not be merged:
 *
 * <ul>
 *   <li>{@link #readable()} indents, because a person has to be able to read a stored state.
 *   <li>{@link #canonical()} emits no insignificant whitespace, because commit ids are
 *       hashed over its output. If ids were derived from the indented form, changing the
 *       indent width would silently change every commit id in the repository.
 * </ul>
 *
 * <p>Unknown properties are ignored on read so that a state written by a newer version of
 * VVCS stays loadable by an older one.
 */
public final class VvcsJson {

    private VvcsJson() {
    }

    /**
     * Indented mapper for files a human may need to read.
     */
    public static ObjectMapper readable() {
        return base()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * Compact mapper whose output is stable enough to hash.
     */
    public static ObjectMapper canonical() {
        return base()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    }

    private static ObjectMapper base() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public static String toJson(Object value) throws IOException {
        return readable().writeValueAsString(value);
    }

    /**
     * A readable mapper, for a caller that needs to navigate the tree rather than write it.
     *
     * <p>Still the configured one, so a test that reads a document and reads it again sees
     * the same rules about unknown properties and empty beans that a caller would.
     */
    public static ObjectMapper getObjectMapper() {
        return readable();
    }

    /**
     * Serializes to the exact bytes whose digest defines a commit id. Stable across runs and
     * across mapping of any object whose property order is itself fixed.
     */
    public static String toCanonicalJson(Object value) throws IOException {
        return canonical().writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) throws IOException {
        return readable().readValue(json, type);
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return read(Files.readString(file, StandardCharsets.UTF_8), type);
    }
}
