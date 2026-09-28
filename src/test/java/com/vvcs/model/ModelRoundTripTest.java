package com.vvcs.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vvcs.parser.KdenliveParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the property the whole repository layer depends on: the VVCS model must survive
 * a write/read cycle unchanged, otherwise a stored state could not be restored.
 *
 * <p>No {@code -parameters} compiler flag is configured, so this test is what proves
 * Jackson can reconstruct the records from their component names alone.
 */
class ModelRoundTripTest {

    private static final Path SAMPLE = Paths.get("sample-project", "sample.kdenlive");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void projectSurvivesAWriteAndReadCycle() throws IOException {
        Project original = new KdenliveParser().parse(SAMPLE);
        String json = MAPPER.writeValueAsString(original);
        Project restored = MAPPER.readValue(json, Project.class);

        assertEquals(original, restored);
    }

    @Test
    void everyAssetSurvivesTheCycle() throws IOException {
        Project project = new KdenliveParser().parse(SAMPLE);
        for (Asset asset : project.assets()) {
            assertEquals(asset, MAPPER.readValue(MAPPER.writeValueAsString(asset), Asset.class),
                    asset.id());
        }
    }

    @Test
    void everyClipSurvivesTheCycle() throws IOException {
        Project project = new KdenliveParser().parse(SAMPLE);
        for (Clip clip : project.timeline().clips()) {
            assertEquals(clip, MAPPER.readValue(MAPPER.writeValueAsString(clip), Clip.class),
                    clip.id());
        }
    }

    @Test
    void frameRatesAndRangesKeepTheirExactValues() throws IOException {
        Project project = new KdenliveParser().parse(SAMPLE);
        Project restored = MAPPER.readValue(MAPPER.writeValueAsString(project), Project.class);

        assertEquals(new Rational(24000, 1001), restored.frameRate());
        assertEquals(new Rational(30000, 1001), restored.asset("asset_001").frameRate());
        assertEquals(new Rational(60000, 1001), restored.asset("asset_002").frameRate());
        assertEquals(new Rational(25, 1), restored.asset("asset_003").frameRate());
        assertEquals(2900L, restored.timeline().duration());
        assertEquals(new Resolution(3840, 2160), restored.resolution());
    }

    @Test
    void derivedAccessorsAreNotWrittenToDisk() throws IOException {
        Project project = new KdenliveParser().parse(SAMPLE);
        String json = MAPPER.writeValueAsString(project);

        assertTrue(json.contains("\"frameRate\""), "frame rate must be persisted");
        for (String leaked : new String[]{"toJson", "assetCount", "clipCount", "hasAudio",
                "endFrame", "toDouble", "isInteger"}) {
            assertTrue(!json.contains("\"" + leaked + "\""),
                    leaked + " must not be serialized");
        }
    }
}
