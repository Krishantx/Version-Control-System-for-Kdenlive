import { el } from '../lib/dom.js';
import { Track } from './Track.js';
import { Ruler } from './Ruler.js';
import { describeFrameRate } from '../lib/timecode.js';

/**
 * One timeline, before or after, with everything it contains.
 *
 * `layout` is the pure result from `lib/layout.js`; this only draws it. That split is what
 * lets the positioning be tested without a browser.
 */
export function Timeline({ side, label, layout, selectedRef, onSelect }) {
  const clipCount = layout.tracks.reduce((n, t) => n + t.clips.length, 0);
  return el('section', { class: `timeline timeline--${side}`, data: {
    side,
    duration: String(layout.duration),
    trackCount: String(layout.tracks.length),
    clipCount: String(clipCount),
  } }, [
    el('header', { class: 'timeline__header' }, [
      el('h2', { class: 'timeline__label', text: label }),
      el('span', { class: 'timeline__facts', text:
        `${layout.duration} frames · ${describeFrameRate(layout.frameRate)} · ` +
        `${layout.tracks.length} tracks · ${clipCount} clips` }),
    ]),
    el('div', { class: 'timeline__ruler-row' }, [
      el('div', { class: 'timeline__gutter' }),
      Ruler({ span: layout.duration, rate: layout.frameRate }),
    ]),
    el('div', { class: 'timeline__tracks' },
      layout.tracks.map((track) =>
        Track({ track, rate: layout.frameRate, side, selectedRef, onSelect }),
      ),
    ),
  ]);
}
