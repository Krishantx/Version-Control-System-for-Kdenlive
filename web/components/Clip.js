import { el } from '../lib/dom.js';
import { shortDuration } from '../lib/timecode.js';

/**
 * One clip, as a block positioned and sized by its frames.
 *
 * `left` and `width` arrive already computed as percentages of the timeline, so this
 * component never does arithmetic on frames. That is deliberate: the arithmetic lives in
 * `lib/layout.js` where it can be tested without a browser, and here it is only applied.
 */
export function Clip({ clip, rate, side, selected, onSelect }) {
  const change = clip.change;
  const changed = Boolean(change);

  const classes = ['clip', `clip--${(side === 'after' ? 'after' : 'before')}`];
  if (changed) classes.push('clip--changed', `clip--${cssKind(change.type)}`);
  if (selected) classes.push('clip--selected');

  // A block drawn wider than its true width is drawn at a minimum so it can be aimed at.
  // Saying so, rather than letting it look like a longer clip, is the honest option.
  const widened = clip.width > clip.exactWidth + 0.5;

  const title = [
    clip.assetName || clip.assetId,
    `${clip.start} – ${clip.start + clip.duration} frames`,
    shortDuration(clip.duration, rate),
  ].join(' · ');

  return el(
    'button',
    {
      class: classes.join(' '),
      style: { left: `${clip.left}%`, width: `${clip.width}%` },
      type: 'button',
      title,
      'aria-label': `${side} ${clip.assetName || clip.clipId}${
        changed ? `, ${change.type}` : ''
      }`,
      data: {
        clipId: clip.clipId,
        trackId: clip.trackId,
        side,
        changed: changed ? 'true' : 'false',
        kind: changed ? change.type : '',
        widened: widened ? 'true' : 'false',
      },
      onclick: () => onSelect && onSelect(change, clip, side),
    },
    [
      el('span', { class: 'clip__name', text: clip.assetName || clip.assetId }),
      el('span', {
        class: 'clip__meta',
        text: `${clip.start}f · ${shortDuration(clip.duration, rate)}`,
      }),
      changed ? el('span', { class: 'clip__badge', text: change.type }) : null,
    ],
  );
}

/** The backend's type, as a class suffix. Unknown types still get a colour. */
function cssKind(type) {
  return String(type || 'CHANGED').toLowerCase().replace(/_/g, '-');
}
