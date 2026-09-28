package com.vvcs.diff;

import com.vvcs.model.TrackType;

/**
 * What happened to a track between two states.
 *
 * <p>{@code beforeIndex} and {@code afterIndex} are the track's position among the tracks
 * of its type. See {@link TrackKey} for why that is the strongest track identity the
 * current model can offer, and what that costs.
 */
public record TrackChange(
        String key,
        ChangeType type,
        TrackType beforeType,
        TrackType afterType,
        int beforeIndex,
        int afterIndex) {
}
