package com.vvcs.diff;

import com.vvcs.model.Asset;

/**
 * An asset as it was and as it became. The media stays the same thing, so a change here is
 * a change to what is known about it, such as a re-hash or a corrected path.
 */
public record AssetChange(
        String assetId,
        ChangeType type,
        Asset before,
        Asset after) {

    public static AssetChange added(Asset after) {
        return new AssetChange(after.id(), ChangeType.ADDED, null, after);
    }

    public static AssetChange removed(Asset before) {
        return new AssetChange(before.id(), ChangeType.REMOVED, before, null);
    }

    public static AssetChange modified(Asset before, Asset after) {
        return new AssetChange(after.id(), ChangeType.MODIFIED, before, after);
    }
}
