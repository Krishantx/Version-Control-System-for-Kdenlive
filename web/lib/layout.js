/**
 * Turning frame coordinates into pixels.
 *
 * Everything here is a pure function of the comparison, the container width and nothing else.
 * That is the point of this file existing separately from the components: the arithmetic that
 * decides where a clip appears is the part most worth testing, and it is far easier to test
 * when it does not need a browser to run it.
 *
 * The rule, unchanged from the backend, is that a frame is a frame. A clip's left edge is
 * its `start` on a scale, and its width is its `duration` on the same scale. Nothing is
 * converted to seconds first, because seconds would need a frame rate and would then be
 * wrong for any clip whose source has a different one.
 */

/**
 * The scale both timelines are drawn against.
 *
 * Before and after share one scale, spanning whichever of the two is longer. If each were
 * drawn against its own duration then a timeline that only grew would look unchanged, which
 * is the single most misleading thing this view could do.
 */
export function computeScale(before, after, width) {
  const span = Math.max(durationOf(before), durationOf(after), 1);
  return {
    span,
    width,
    toPx: (frame) => (frame / span) * width,
    framesToPx: (count) => (count / span) * width,
  };
}

function durationOf(snapshot) {
  return snapshot && Number.isFinite(snapshot.duration) ? snapshot.duration : 0;
}

/**
 * Lays out one timeline: every track, every clip, positioned by its start and sized by its
 * duration.
 *
 * `changeIndex` comes from `changes.js` and is looked up per side, so a clip that changed
 * tracks is marked on the track it left and on the track it joined, which is the only way
 * both timelines can show what happened.
 */
export function layoutTimeline(snapshot, scale, changeIndex, side) {
  const rate = snapshot.frameRate;
  return {
    stateId: snapshot.stateId,
    duration: snapshot.duration,
    frameRate: rate,
    tracks: (snapshot.tracks || []).map((track) => ({
      id: track.id,
      type: track.type,
      index: track.index,
      label: trackLabel(track),
      clips: (track.clips || []).map((clip) => {
        const change = changeIndex ? changeIndex.get(side, clip.clipId, clip.trackId) : null;
        return {
          ...clip,
          left: scale.toPx(clip.start),
          width: Math.max(scale.framesToPx(clip.duration), MIN_CLIP_PX),
          // The true width, kept beside the drawn one, so a test can tell a genuinely tiny
          // clip from one that was only widened to stay clickable.
          exactWidth: scale.framesToPx(clip.duration),
          timecode: `${clip.start}f`,
          change,
        };
      }),
    })),
  };
}

/** Enough of a clip to be aimed at with a mouse. */
export const MIN_CLIP_PX = 3;

/**
 * A track's label: `VIDEO 2`, `AUDIO 1`.
 *
 * The backend carries a track's type and its position, not a name, so the label is derived
 * here rather than invented in the model. Numbering runs downwards, as it does in an editor.
 */
export function trackLabel(track) {
  return `${track.type} ${track.index + 1}`;
}

/**
 * Ticks for the ruler: whole frames, at an interval chosen to be readable.
 *
 * The interval is a power of ten scaled by one of 1, 2 or 5, so the numbers on the ruler are
 * round ones a person can multiply in their head rather than whatever a divisor produced.
 *
 * The span itself is always the last tick. Without it a timeline that is not a whole number of
 * intervals ends in blank space, and the reader has to guess whether the last block runs to
 * the edge or stops short of it.
 */
export function rulerTicks(span, targetCount = 8) {
  if (!(span > 0)) return [];
  const rawStep = span / Math.max(1, targetCount);
  const magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
  const normalized = rawStep / magnitude;
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;

  const frames = new Set([0]);
  for (let frame = step; frame < span; frame += step) {
    frames.add(frame);
  }
  frames.add(span);

  return [...frames]
    .sort((a, b) => a - b)
    .map((frame) => ({
      frame,
      left: (frame / span) * 100,
      major: frame % (step * 2) === 0,
    }));
}

/** Where every change sits, as a share of the width, so the list can show it in context. */
export function changePosition(change, scale) {
  const side = change.after || change.before;
  if (!side || !Number.isFinite(side.timelineStart)) return null;
  return {
    left: scale.toPx(side.timelineStart),
    width: Math.max(scale.framesToPx(side.timelineDuration), MIN_CLIP_PX),
  };
}
