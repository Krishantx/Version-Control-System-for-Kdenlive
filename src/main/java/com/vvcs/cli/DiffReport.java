package com.vvcs.cli;

import com.vvcs.diff.AssetChange;
import com.vvcs.diff.ChangeType;
import com.vvcs.diff.ClipChange;
import com.vvcs.diff.ClipOccurrence;
import com.vvcs.diff.TimelineDiff;
import com.vvcs.diff.TrackChange;

import java.io.PrintStream;
import java.util.stream.Collectors;

/**
 * Renders a {@link TimelineDiff} for a person reading a terminal.
 *
 * <p>Formatting lives here rather than in {@link com.vvcs.diff.DiffEngine} so that the
 * engine can be used by anything at all. Nothing in the diff model is written to suit a
 * terminal: the report reads the facts and phrases them, and never the other way round.
 */
final class DiffReport {

    private DiffReport() {
    }

    static void print(TimelineDiff diff, PrintStream out) {
        if (diff.isEmpty()) {
            out.println("no changes");
            return;
        }
        printSummary(diff, out);
        if (!diff.assetChanges().isEmpty()) {
            out.println();
            out.println("assets:");
            for (AssetChange change : diff.assetChanges()) {
                out.println("  " + change.assetId() + "  " + change.type().name().toLowerCase());
            }
        }
        if (!diff.trackChanges().isEmpty()) {
            out.println();
            out.println("tracks:");
            for (TrackChange change : diff.trackChanges()) {
                out.println("  " + change.key() + "  " + describe(change));
            }
        }
        if (!diff.clipChanges().isEmpty()) {
            out.println();
            out.println("clips:");
            for (ClipChange change : diff.clipChanges()) {
                out.println("  " + change.clipId() + "  " + describe(change));
            }
        }
    }

    private static void printSummary(TimelineDiff diff, PrintStream out) {
        String counts = diff.clipChanges().stream()
                .map(change -> change.kinds().stream().map(DiffReport::lowercase)
                        .collect(Collectors.joining("+")))
                .collect(Collectors.joining(", "));
        out.println("clips " + diff.clipChanges().size()
                + " (" + (counts.isEmpty() ? "none" : counts) + ")");
        if (diff.fromDuration() != diff.toDuration()) {
            out.println("timeline " + diff.fromDuration() + " -> " + diff.toDuration() + " frames");
        }
    }

    private static String describe(TrackChange change) {
        return switch (change.type()) {
            case ADDED -> "added as " + change.afterType().name().toLowerCase();
            case REMOVED -> "removed, was " + change.beforeType().name().toLowerCase();
            default -> "type " + change.beforeType().name().toLowerCase()
                    + " -> " + change.afterType().name().toLowerCase();
        };
    }

    private static String describe(ClipChange change) {
        if (change.type() == ChangeType.ADDED) {
            return "added on " + track(change.after())
                    + " at " + change.after().timelineStart()
                    + " for " + change.after().timelineDuration();
        }
        if (change.type() == ChangeType.REMOVED) {
            return "removed from " + track(change.before())
                    + " at " + change.before().timelineStart()
                    + " for " + change.before().timelineDuration();
        }
        return change.kinds().stream().map(DiffReport::lowercase)
                .collect(Collectors.joining("+"))
                + ": " + detail(change);
    }

    private static String detail(ClipChange change) {
        ClipOccurrence before = change.before();
        ClipOccurrence after = change.after();
        StringBuilder text = new StringBuilder();
        if (change.kinds().contains(ChangeType.TRACK_CHANGED)) {
            text.append(track(before)).append(" -> ").append(track(after));
        } else {
            text.append(track(after));
        }
        if (change.kinds().contains(ChangeType.MOVED)) {
            text.append(", ").append(before.timelineStart()).append(" -> ")
                    .append(after.timelineStart());
        }
        if (change.kinds().contains(ChangeType.TRIMMED)) {
            if (before.timelineDuration() != after.timelineDuration()) {
                text.append(", length ").append(before.timelineDuration()).append(" -> ")
                        .append(after.timelineDuration());
            }
            if (before.sourceIn() != after.sourceIn() || before.sourceOut() != after.sourceOut()) {
                text.append(", source ").append(span(before)).append(" -> ").append(span(after));
            }
        }
        if (change.kinds().contains(ChangeType.REORDERED)) {
            text.append(", place ").append(before.orderOnTrack() + 1).append(" -> ")
                    .append(after.orderOnTrack() + 1);
        }
        if (change.kinds().contains(ChangeType.MODIFIED)) {
            text.append(before.assetId().equals(after.assetId())
                    ? ", changed"
                    : ", now uses " + after.assetId() + " instead of " + before.assetId());
        }
        return text.toString();
    }

    /**
     * The range of source frames used. Kdenlive's out point is inclusive, which is stated
     * here so that a reader does not mistake the closed range for an off-by-one.
     */
    private static String span(ClipOccurrence occurrence) {
        return "[" + occurrence.sourceIn() + ".." + occurrence.sourceOut() + "]";
    }

    private static String track(ClipOccurrence occurrence) {
        return occurrence.trackId();
    }

    private static String lowercase(ChangeType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }
}
