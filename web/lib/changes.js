/**
 * Connecting the backend's changes to the clips on the timelines.
 *
 * The rule this file exists to enforce: the backend already decided what happened. A clip that
 * moved is `MOVED` because the diff engine compared it and said so, and this file does not get
 * to have an opinion. It never looks at a position and asks whether it changed.
 *
 * All it does is join two things that were sent separately: a list of changes, and two
 * timelines. A change names its clips by `clipId` and `trackId`, which together identify a
 * clip within one side, so that is the key. Both sides are indexed, because a clip that moved
 * between tracks appears on one timeline under its old track and the other under its new one,
 * and a viewer that only marked one of them would be showing half the story.
 */

/** The identity of a clip within one side of a comparison. */
export function clipRef(clipId, trackId) {
  return `${clipId}|${trackId}`;
}

/**
 * Indexes changes by the clip they describe, per side.
 *
 * @param changes the `clipChanges` array from the comparison
 * @returns an object with `before` and `after` lookup methods
 */
export function indexChanges(changes) {
  const before = new Map();
  const after = new Map();
  for (const change of changes || []) {
    if (change.before) {
      before.set(clipRef(change.before.clipId, change.before.trackId), change);
    }
    if (change.after) {
      after.set(clipRef(change.after.clipId, change.after.trackId), change);
    }
  }
  return {
    before,
    after,
    get(side, clipId, trackId) {
      const table = side === 'after' ? after : before;
      return table.get(clipRef(clipId, trackId)) || null;
    },
  };
}

/**
 * The change for a clip, whichever side it is on.
 *
 * Used by the inspector when a clip block is clicked, so a reader who clicks a clip on the
 * after timeline and one who clicks the same clip on the before timeline both get an answer.
 */
export function findChangeForClip(changeIndex, side, clipId, trackId) {
  return changeIndex.get(side, clipId, trackId);
}

/**
 * A change as a list entry: what happened, and to which clip.
 *
 * `label` is presentational and lives here, in the frontend, because a sentence about a change
 * is a thing to show, not a fact to store. The backend sends the type and both sides; nothing
 * here infers the type.
 */
export function describeChange(change) {
  const side = change.after || change.before;
  const kind = change.type;
  return {
    change,
    type: kind,
    kinds: change.kinds || [kind],
    clipId: side.clipId,
    trackId: side.trackId,
    label: labelFor(kind),
    symbol: symbolFor(kind),
  };
}

/** The word a person reads. Unknown types are shown as sent rather than hidden. */
export function labelFor(type) {
  switch (type) {
    case 'ADDED':
      return 'Added';
    case 'REMOVED':
      return 'Removed';
    case 'MOVED':
      return 'Moved';
    case 'TRIMMED':
      return 'Trimmed';
    case 'REORDERED':
      return 'Reordered';
    case 'TRACK_CHANGED':
      return 'Moved to another track';
    case 'MODIFIED':
      return 'Modified';
    default:
      return type || 'Changed';
  }
}

/** A single character, for a list that has to stay narrow. */
export function symbolFor(type) {
  switch (type) {
    case 'ADDED':
      return '+';
    case 'REMOVED':
      return '−';
    case 'MOVED':
    case 'TRACK_CHANGED':
      return '↕';
    case 'TRIMMED':
      return '~';
    case 'REORDERED':
      return '⇄';
    case 'MODIFIED':
      return '±';
    default:
      return '•';
  }
}

/**
 * The rows of the inspector for one change.
 *
 * Generated from the two sides the backend sent. A row only appears when it has a fact to
 * show, so an added clip has no "from" position and does not pretend to.
 */
export function inspectorRows(change, snapshot) {
  const before = change.before;
  const after = change.after;
  const rows = [];

  if (before && after) {
    if (before.trackId !== after.trackId) {
      rows.push({ label: 'Track', from: trackName(before.trackId, snapshot, 'before'),
                  to: trackName(after.trackId, snapshot, 'after') });
    }
    if (before.timelineStart !== after.timelineStart) {
      rows.push({ label: 'Position', from: `${before.timelineStart} f`,
                  to: `${after.timelineStart} f` });
    }
    if (before.timelineDuration !== after.timelineDuration) {
      rows.push({ label: 'Duration', from: `${before.timelineDuration} f`,
                  to: `${after.timelineDuration} f` });
    }
    if (before.sourceIn !== after.sourceIn || before.sourceOut !== after.sourceOut) {
      rows.push({ label: 'Source range',
                  from: `${before.sourceIn}–${before.sourceOut}`,
                  to: `${after.sourceIn}–${after.sourceOut}` });
    }
    if (before.assetId !== after.assetId) {
      rows.push({ label: 'Media', from: assetName(before.assetId, snapshot, 'before'),
                  to: assetName(after.assetId, snapshot, 'after') });
    }
  } else if (after) {
    rows.push({ label: 'Media', value: assetName(after.assetId, snapshot, 'after') });
    rows.push({ label: 'Track', value: trackName(after.trackId, snapshot, 'after') });
    rows.push({ label: 'Position', value: `${after.timelineStart} f` });
    rows.push({ label: 'Duration', value: `${after.timelineDuration} f` });
    if (after.sourceIn !== undefined) {
      rows.push({ label: 'Source range', value: `${after.sourceIn}–${after.sourceOut}` });
    }
  } else if (before) {
    rows.push({ label: 'Media', value: assetName(before.assetId, snapshot, 'before') });
    rows.push({ label: 'Track', value: trackName(before.trackId, snapshot, 'before') });
    rows.push({ label: 'Position', value: `${before.timelineStart} f` });
    rows.push({ label: 'Duration', value: `${before.timelineDuration} f` });
    if (before.sourceIn !== undefined) {
      rows.push({ label: 'Source range', value: `${before.sourceIn}–${before.sourceOut}` });
    }
  }
  return rows;
}

function findTrack(snapshot, trackId) {
  if (!snapshot || !snapshot.tracks) return null;
  return snapshot.tracks.find((t) => t.id === trackId) || null;
}

/**
 * The snapshot a side refers to.
 *
 * A track's readable name lives in the snapshot that contains it, so the "before" name has to
 * be looked up in the before snapshot. Taking the wrong one silently produced raw ids such as
 * `track_001` on one side and `VIDEO 1` on the other, which is exactly the kind of half-answer
 * that reads as a bug in the data rather than in the lookup.
 */
function snapshotForSide(snapshots, side) {
  if (!snapshots) return null;
  if (snapshots.tracks) return snapshots;
  return side === 'after' ? snapshots.after : snapshots.before;
}

function trackName(trackId, snapshots, side) {
  const track = findTrack(snapshotForSide(snapshots, side), trackId);
  return track ? `${track.type} ${track.index + 1}` : trackId;
}

function assetName(assetId, snapshots, side) {
  const snapshot = snapshotForSide(snapshots, side);
  if (!snapshot || !snapshot.tracks) return assetId;
  for (const track of snapshot.tracks) {
    for (const clip of track.clips || []) {
      if (clip.assetId === assetId) return clip.assetName || assetId;
    }
  }
  return assetId;
}

/** The ids a comparison refers to, for links and for the address bar. */
export function comparisonIds(comparison) {
  return {
    a: comparison && comparison.from ? comparison.from.shortId : null,
    b: comparison && comparison.to ? comparison.to.shortId : null,
  };
}
