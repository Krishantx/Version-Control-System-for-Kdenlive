package com.vvcs.comparison;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.vvcs.diff.TimelineDiff;

/**
 * One comparison of two points in a project's history: what it looked like, what it looks
 * like now, and what changed between them.
 *
 * <p>This is the object a frontend is meant to receive, and it is the whole of what it
 * receives. It answers a single question, "what happened between these two commits", and
 * answers it with three parts:
 *
 * <pre>
 *   before  the timeline as it was, drawn in full
 *   after   the timeline as it is, drawn in full
 *   changes what differs between them
 * </pre>
 *
 * <p>Both snapshots are present even when nothing changed, because a consumer decides what to
 * display and a comparison that omitted the timelines when they were identical would have
 * the consumer special-casing exactly the case that is easiest to get wrong.
 *
 * <h2>There is one diff type, and this is it</h2>
 *
 * <p>{@code changes} is a {@link TimelineDiff}, the object the core diff engine already
 * produces. This type does not restate, wrap, or translate those changes into a second
 * vocabulary, because two vocabularies for the same facts is how a system ends up with two
 * answers. The comparison adds the two snapshots and the two commit labels; every fact about
 * what changed comes from the one engine that already computes it.
 *
 * <h2>What a consumer does not need to know</h2>
 *
 * <p>Nothing here mentions Kdenlive XML, producers, playlists, the {@code kdenlive:id} an
 * editor assigns, the layout of a repository on disk, or how matching is decided. The
 * snapshots are already flattened and named in domain terms, and the changes already carry
 * both sides of every difference, so rendering a timeline and annotating it needs no further
 * questions and no second pass of the diff algorithm.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineComparison(
        CommitRef from,
        CommitRef to,
        TimelineSnapshot before,
        TimelineSnapshot after,
        TimelineDiff changes) {

    public TimelineComparison {
        if (before == null) {
            throw new IllegalArgumentException("a comparison needs the state it starts from");
        }
        if (after == null) {
            throw new IllegalArgumentException("a comparison needs the state it ends at");
        }
        if (changes == null) {
            throw new IllegalArgumentException("a comparison needs its changes");
        }
    }

    /**
     * True when the two states are semantically identical, snapshots notwithstanding.
     *
     * <p>Excluded from the JSON for the same reason as {@link TimelineDiff#isEmpty()}: it is
     * derived from the changes, and a consumer that reads them can tell for itself.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** The frame length the timeline went from, in project frames. */
    public long fromDuration() {
        return before.duration();
    }

    /** The frame length the timeline is now, in project frames. */
    public long toDuration() {
        return after.duration();
    }
}
