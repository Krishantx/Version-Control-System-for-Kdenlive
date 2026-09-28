package com.vvcs.parser;

import com.vvcs.model.Project;
import com.vvcs.model.Resolution;
import com.vvcs.model.Timeline;
import com.vvcs.parser.internal.AssetRegistry;
import com.vvcs.parser.internal.KdenliveDocument;
import com.vvcs.parser.internal.KdenliveDocumentReader;
import com.vvcs.parser.internal.SequenceLocator;
import com.vvcs.parser.internal.TimelineBuilder;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Reads a Kdenlive project and returns it as a VVCS {@link Project}.
 *
 * <p>This class is an adapter and nothing more. Kdenlive's producers, chains, playlists and
 * tractors exist only inside {@code com.vvcs.parser.internal}; what leaves this class is the
 * editor-independent model, so a Premiere or OpenTimelineIO importer can produce exactly the
 * same result without the core model knowing either format.
 *
 * <p>Parsing is deterministic: identifiers are assigned in document order, never randomly,
 * so parsing the same file twice always yields the same project.
 *
 * <pre>{@code
 * Project project = new KdenliveParser().parse(Paths.get("project.kdenlive"));
 * }</pre>
 */
public final class KdenliveParser {

    /** Identifier of the single timeline VVCS extracts from a Kdenlive project. */
    public static final String TIMELINE_ID = "timeline_001";

    private static final Logger LOG = Logger.getLogger(KdenliveParser.class.getName());

    /**
     * Parses a Kdenlive project file.
     *
     * @param kdenliveFile the {@code .kdenlive} file to read
     * @return the project's assets and timeline
     * @throws KdenliveParserException if the file is missing, the XML is malformed, no main
     *                                 sequence can be found, an entry references an unknown
     *                                 producer, or a media resource cannot be resolved
     */
    public Project parse(Path kdenliveFile) {
        KdenliveDocument document = new KdenliveDocumentReader().read(kdenliveFile);
        LOG.fine(() -> "read " + document.producers().size() + " producers, "
                + document.playlists().size() + " playlists and "
                + document.tractors().size() + " tractors");

        SequenceLocator locator = new SequenceLocator();
        KdenliveDocument.TractorNode sequence = locator.locate(document);

        AssetRegistry assets = AssetRegistry.build(document, kdenliveFile);
        Timeline timeline = new TimelineBuilder(document, assets, TIMELINE_ID)
                .build(sequence, locator.sequenceName(sequence));

        KdenliveDocument.Profile profile = document.profile();
        return new Project(
                projectName(kdenliveFile),
                new Resolution(profile.width(), profile.height()),
                document.frameRate(),
                assets.assets(),
                timeline);
    }

    private static String projectName(Path kdenliveFile) {
        String fileName = kdenliveFile.getFileName() == null
                ? kdenliveFile.toString()
                : kdenliveFile.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        return extension > 0 ? fileName.substring(0, extension) : fileName;
    }
}
