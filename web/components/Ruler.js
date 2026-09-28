import { el } from '../lib/dom.js';
import { rulerTicks } from '../lib/layout.js';
import { timecode } from '../lib/timecode.js';

/**
 * The ruler above a timeline: whole frames at a readable interval.
 *
 * The numbers are frames, because frames are what the backend speaks and what a comparison is
 * about. Seconds are shown underneath in small type for anyone who reads time rather than
 * positions, and they are derived from the same frame through the project's own rational
 * rate, so the two lines cannot drift apart.
 */
export function Ruler({ span, rate }) {
  return el('div', { class: 'ruler', data: { span: String(span) } },
    rulerTicks(span).map((tick) =>
      el('div', {
        class: `ruler__tick${tick.major ? ' ruler__tick--major' : ''}`,
        style: { left: `${tick.left}%` },
        data: { frame: String(tick.frame) },
      }, [
        el('span', { class: 'ruler__frames', text: String(tick.frame) }),
        el('span', { class: 'ruler__time', text: timecode(tick.frame, rate) }),
      ]),
    ),
  );
}
