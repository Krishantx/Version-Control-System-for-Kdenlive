import { el } from '../lib/dom.js';
import { Clip } from './Clip.js';

/**
 * One track, labelled by type and position.
 *
 * A track with no clips still gets a row. An empty track is a real part of the structure, and
 * hiding it would make a project with an unused track look like a project that never had one.
 */
export function Track({ track, rate, side, selectedRef, onSelect }) {
  return el('div', { class: `track track--${track.type.toLowerCase()}`, data: {
    trackId: track.id,
    type: track.type,
    index: String(track.index),
    empty: track.clips.length === 0 ? 'true' : 'false',
  } }, [
    el('div', { class: 'track__label' }, [
      el('span', { class: 'track__type', text: track.type }),
      el('span', { class: 'track__index', text: String(track.index + 1) }),
    ]),
    el('div', { class: 'track__lane' },
      track.clips.map((clip) =>
        Clip({
          clip,
          rate,
          side,
          selected: isSelected(selectedRef, clip, side),
          onSelect,
        }),
      ),
    ),
  ]);
}

function isSelected(selectedRef, clip, side) {
  if (!selectedRef) return false;
  return (
    selectedRef.side === side &&
    selectedRef.clipId === clip.clipId &&
    selectedRef.trackId === clip.trackId
  );
}
