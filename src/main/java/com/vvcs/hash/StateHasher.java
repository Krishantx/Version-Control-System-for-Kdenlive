package com.vvcs.hash;

import com.vvcs.model.Project;
import com.vvcs.serialization.VvcsJson;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Derives the identity of a project state from what the state contains.
 *
 * <p>A state id is the SHA-256 of the canonical JSON encoding of the {@link Project} it
 * holds. Nothing chooses the number: two commits of the same edit arrive at the same id
 * without anyone comparing them, which is what makes deduplication fall out of the scheme
 * rather than needing a separate search.
 *
 * <p>The id is not truncated. A state is the largest object VVCS stores, so it is the one
 * where a collision would be most expensive, and a 64 character file name is not a real
 * cost.
 */
public final class StateHasher {

    private StateHasher() {
    }

    /**
     * The identifier {@code project} will be stored under.
     */
    public static String idOf(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("cannot identify a null project");
        }
        try {
            return ContentHash.sha256Utf8(VvcsJson.toCanonicalJson(project));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot digest project state", e);
        }
    }

    /**
     * Whether {@code stored} still holds the content its identifier claims. A repository
     * calls this before writing, so that a mislabelled state is refused rather than stored
     * under an identity that will never match it again.
     */
    public static boolean matchesIdOf(Project project, String id) {
        return idOf(project).equals(id);
    }
}
