import { el } from '../lib/dom.js';
import { describeChange, inspectorRows } from '../lib/changes.js';

/**
 * Everything about one selected change.
 *
 * The rows are built from the two sides the backend sent, and a row that has nothing to
 * compare is left out. An added clip has no earlier position, so there is no "from" to show,
 * and printing an empty one would suggest a value the comparison does not contain.
 */
export function ChangeInspector({ change, comparison, onClear }) {
  if (!change) {
    return el('section', { class: 'inspector inspector--empty' }, [
      el('h2', { class: 'inspector__title', text: 'Selected change' }),
      el('p', { class: 'inspector__hint', text: 'Pick a clip, or a change from the list.' }),
    ]);
  }

  const described = describeChange(change);
  const rows = inspectorRows(change, {
    before: comparison.before,
    after: comparison.after,
  });
  const side = change.after || change.before;

  return el('section', { class: 'inspector', data: { type: described.type } }, [
    el('h2', { class: 'inspector__title' }, [
      'Selected change ',
      el('button', {
        class: 'inspector__clear', type: 'button', text: 'clear', onclick: onClear,
      }),
    ]),
    el('div', { class: 'inspector__headline' }, [
      el('span', { class: `inspector__symbol inspector__symbol--${described.type.toLowerCase()}`,
                   text: described.symbol }),
      el('span', { class: 'inspector__label', text: described.label }),
      el('code', { class: 'inspector__clip', text: side.clipId || '' }),
    ]),
    described.kinds.length > 1
      ? el('p', { class: 'inspector__kinds', text: `Also: ${described.kinds.slice(1).join(', ')}` })
      : null,
    el('dl', { class: 'inspector__rows' },
      rows.flatMap((row) => [
        el('dt', { text: row.label }),
        row.value !== undefined
          ? el('dd', { class: 'inspector__value', text: row.value })
          : el('dd', { class: 'inspector__value' }, [
              el('span', { class: 'inspector__from', text: row.from }),
              el('span', { class: 'inspector__arrow', text: '→' }),
              el('span', { class: 'inspector__to', text: row.to }),
            ]),
      ]),
    ),
  ]);
}
