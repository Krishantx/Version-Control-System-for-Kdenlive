import { el } from '../lib/dom.js';
import { describeChange, symbolFor } from '../lib/changes.js';

/**
 * Every change, as a list.
 *
 * Each row says what the backend said happened and to which clip. The word and the symbol are
 * chosen in the frontend from `change.type`, because a word is a thing to show; the type
 * itself arrives decided, and is shown verbatim as a badge so that a type this frontend has
 * never heard of is still legible rather than silently dropped.
 */
export function ChangeList({ changes, selected, onSelect }) {
  if (!changes.length) {
    return el('section', { class: 'changes changes--none' }, [
      el('h2', { class: 'changes__title', text: 'Changes' }),
      el('p', { class: 'changes__empty', text: 'No structural changes between these commits.' }),
    ]);
  }

  const counts = new Map();
  for (const change of changes) {
    counts.set(change.type, (counts.get(change.type) || 0) + 1);
  }

  return el('section', { class: 'changes' }, [
    el('h2', { class: 'changes__title' }, [
      'Changes ',
      el('span', { class: 'changes__count', text: String(changes.length) }),
    ]),
    el('ul', { class: 'changes__legend' },
      [...counts.entries()].map(([type, n]) =>
        el('li', { class: `legend legend--${type.toLowerCase()}`, data: { type } }, [
          // The legend counts types, so it asks for a type's symbol rather than describing a
          // change it does not have.
          el('span', { class: 'legend__symbol', text: symbolFor(type) }),
          el('span', { class: 'legend__label', text: type }),
          el('span', { class: 'legend__count', text: String(n) }),
        ]),
      ),
    ),
    el('ul', { class: 'changes__list', data: { count: String(changes.length) } },
      changes.map((change) => {
        const described = describeChange(change);
        const isSelected = selected && selected.change === change;
        return el('li', {}, [
          el('button', {
            class: `change change--${described.type.toLowerCase()}${
              isSelected ? ' change--selected' : ''
            }`,
            type: 'button',
            data: { type: described.type, clipId: described.clipId || '' },
            onclick: () => onSelect(change),
          }, [
            el('span', { class: 'change__symbol', text: described.symbol }),
            el('span', { class: 'change__label', text: described.label }),
            el('code', { class: 'change__clip', text: described.clipId || '' }),
            described.kinds.length > 1
              ? el('span', { class: 'change__kinds', text: described.kinds.join(' + ') })
              : null,
          ]),
        ]);
      }),
    ),
  ]);
}
