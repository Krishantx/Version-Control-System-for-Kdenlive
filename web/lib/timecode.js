/**
 * Rendering a frame position as something a person can read.
 *
 * This is a deliberate mirror of `com.vvcs.format.Timecode` on the server. The two agree by
 * construction on the arithmetic and are checked against the same cases, because a frame
 * shown as one timecode on the server and another in the browser is the kind of discrepancy
 * nobody notices until someone trusts a number.
 *
 * Neither module is the source of truth. Frames are. This exists so that a person does not
 * have to divide by 1001 in their head.
 */

/** The whole number of frames per second a rate is treated as, for display purposes. */
export function nominalFps(rate) {
  if (!rate || !rate.numerator) return 1;
  return Math.max(1, Math.round(rate.numerator / rate.denominator));
}

/** Exact seconds, for a frame counted at a rate. */
export function toSeconds(frame, rate) {
  return (frame * rate.denominator) / rate.numerator;
}

/** `HH:MM:SS.mmm`, correct at any rate including a fractional one. */
export function timecode(frame, rate) {
  if (!rate || !rate.numerator) return '00:00:00.000';
  const totalMillis = Math.round(toSeconds(frame, rate) * 1000);
  const totalSeconds = Math.floor(totalMillis / 1000);
  const pad = (n, width) => String(n).padStart(width, '0');
  return `${pad(Math.floor(totalSeconds / 3600), 2)}:${pad(
    Math.floor(totalSeconds / 60) % 60,
    2,
  )}:${pad(totalSeconds % 60, 2)}.${pad(totalMillis % 1000, 3)}`;
}

/**
 * `HH:MM:SS:FF`. Seconds and the frame field come from the frame count together, so they
 * cannot disagree the way they would if each were rounded on its own.
 *
 * At a fractional rate the seconds are counted at the nominal rate, so this can differ from
 * `timecode` by a second after a long enough span. `timecode` is the one to trust there.
 */
export function frames(frame, rate) {
  const fps = nominalFps(rate);
  const wholeSeconds = Math.floor(frame / fps);
  const within = ((frame % fps) + fps) % fps;
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(Math.floor(wholeSeconds / 3600))}:${pad(
    Math.floor(wholeSeconds / 60) % 60,
  )}:${pad(wholeSeconds % 60)}:${pad(within)}`;
}

/**
 * A frame rate for a heading: `23.976 fps`, `30 fps`, `25 fps`.
 *
 * Shown from the rational rather than from a rounded number, because a project at 24000/1001
 * is not a project at 24, and rounding it in the label invites the same error in the head.
 */
export function describeFrameRate(rate) {
  if (!rate || !rate.numerator) return 'unknown rate';
  const exact = rate.numerator / rate.denominator;
  if (Number.isInteger(exact)) return `${exact} fps`;
  return `${exact.toFixed(3)} fps`;
}

/** A short duration for a clip block: `4.2s`, or `1m 20s` past a minute. */
export function shortDuration(framesCount, rate) {
  const seconds = toSeconds(framesCount, rate);
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds - m * 60);
  return s === 60 ? `${m + 1}m` : `${m}m ${s}s`;
}
