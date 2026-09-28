package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * The complete editor-independent description of a video project: the media it can use
 * and the single timeline it arranges that media into.
 *
 * <p>This model is the stable contract of VVCS. Importers such as the Kdenlive parser are
 * adapters that must not leak their source format's structure into it.
 */
public record Project(
        String name,
        Resolution resolution,
        Rational frameRate,
        List<Asset> assets,
        Timeline timeline) {

    public Project {
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    @JsonIgnore
    public Asset asset(String assetId) {
        return assets.stream()
                .filter(a -> a.id().equals(assetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such asset: " + assetId));
    }

    @JsonIgnore
    public int assetCount() {
        return assets.size();
    }
}
