package com.vvcs.parser.internal;

import com.vvcs.parser.KdenliveParserException;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the project's main sequence inside a parsed document.
 *
 * <p>A Kdenlive project contains several tractors that are not sequences: the project
 * tractor that merely wraps the sequence, the individual track tractors, and any tractor
 * that only points back at the project bin. The sequence is identified by, in order:
 *
 * <ol>
 *   <li>the track of the tractor marked as the project tractor (the timeline wrapper);</li>
 *   <li>the sequence-properties tractor referenced from the project bin;</li>
 *   <li>the first tractor carrying sequence properties.</li>
 * </ol>
 */
public final class SequenceLocator {

    private static final String PROJECT_TRACTOR = "kdenlive:projectTractor";
    private static final String SEQUENCE_DOCUMENT_UUID = "kdenlive:sequenceproperties.documentuuid";
    private static final String SEQUENCE_CLIP_NAME = "kdenlive:clipname";

    public KdenliveDocument.TractorNode locate(KdenliveDocument document) {
        return viaProjectTractor(document)
                .or(() -> viaBinReference(document))
                .or(() -> firstSequenceTractor(document))
                .orElseThrow(() -> new KdenliveParserException(
                        KdenliveParserException.Reason.SEQUENCE_NOT_FOUND,
                        "no main sequence found in the Kdenlive project; expected a tractor with '"
                                + PROJECT_TRACTOR + "' or '" + SEQUENCE_DOCUMENT_UUID + "'"));
    }

    /** True when the tractor is a sequence rather than a wrapper, a lane or a bin container. */
    public boolean isSequence(KdenliveDocument.TractorNode tractor) {
        return tractor.property(SEQUENCE_DOCUMENT_UUID) != null;
    }

    public String sequenceName(KdenliveDocument.TractorNode sequence) {
        String name = sequence.property(SEQUENCE_CLIP_NAME);
        return name == null || name.isBlank() ? "Sequence 1" : name;
    }

    private Optional<KdenliveDocument.TractorNode> viaProjectTractor(KdenliveDocument document) {
        for (KdenliveDocument.TractorNode tractor : document.tractors().values()) {
            if (!isTrue(tractor.property(PROJECT_TRACTOR))) {
                continue;
            }
            for (KdenliveDocument.TrackRef track : tractor.tracks()) {
                KdenliveDocument.TractorNode candidate = document.tractor(track.producerId());
                if (candidate != null) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<KdenliveDocument.TractorNode> viaBinReference(KdenliveDocument document) {
        Set<String> sequenceIds = new LinkedHashSet<>();
        for (KdenliveDocument.TractorNode tractor : document.tractors().values()) {
            if (isSequence(tractor)) {
                sequenceIds.add(tractor.id());
            }
        }
        if (sequenceIds.isEmpty()) {
            return Optional.empty();
        }
        for (KdenliveDocument.PlaylistNode playlist : document.playlists().values()) {
            for (KdenliveDocument.EntryNode entry : playlist.entries()) {
                if (sequenceIds.contains(entry.producerId())) {
                    return Optional.of(document.tractor(entry.producerId()));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<KdenliveDocument.TractorNode> firstSequenceTractor(KdenliveDocument document) {
        return document.tractors().values().stream().filter(this::isSequence).findFirst();
    }

    private static boolean isTrue(String value) {
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim()));
    }
}
