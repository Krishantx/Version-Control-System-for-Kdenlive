import { el, render, clear } from '../lib/dom.js';
import { fetchDiff } from '../api/diff.js';
import { computeScale, layoutTimeline, changePosition } from '../lib/layout.js';
import { indexChanges } from '../lib/changes.js';
import { describeFrameRate } from '../lib/timecode.js';
import { ComparisonHeader } from '../components/ComparisonHeader.js';
import { Timeline } from '../components/Timeline.js';
import { ChangeList } from '../components/ChangeList.js';
import { ChangeInspector } from '../components/ChangeInspector.js';

/**
 * The comparison page: two commits in, one comparison out.
 *
 * The order of work is the same as the server's, and deliberately so. The page asks for a
 * comparison, gets the two snapshots and the change list it already contains, lays the
 * snapshots out against one shared scale, and draws. It never asks the server a second
 * question about the same pair, and it never works out what changed for itself.
 */
export class DiffPage {
  constructor(root) {
    this.root = root;
    this.comparison = null;
    this.selected = null;
    this.selectedRef = null;
  }

  async load(commitA, commitB) {
    render(this.root, Status({ kind: 'loading', message: 'Loading comparison…' }));
    try {
      const comparison = await fetchDiff(commitA, commitB);
      this.comparison = comparison;
      this.selected = null;
      this.selectedRef = null;
      this.draw();
    } catch (error) {
      render(this.root, Status({ kind: 'error', error }));
    }
  }

  draw() {
    const comparison = this.comparison;
    const changes = (comparison.changes && comparison.changes.clipChanges) || [];
    const changeIndex = indexChanges(changes);
    // Both timelines share one scale, or a timeline that merely grew would look unchanged.
    const scale = computeScale(comparison.before, comparison.after, 100);

    const before = layoutTimeline(comparison.before, scale, changeIndex, 'before');
    const after = layoutTimeline(comparison.after, scale, changeIndex, 'after');

    const onSelect = (change, clip, side) => {
      if (!change) {
        this.selected = null;
        this.selectedRef = null;
      } else {
        this.selected = change;
        this.selectedRef = { side, clipId: clip.clipId, trackId: clip.trackId };
      }
      this.draw();
    };

    render(this.root, [
      ComparisonHeader({ comparison }),
      el('main', { class: 'page' }, [
        el('div', { class: 'page__facts', data: { frames: String(scale.span) } }, [
          Fact('Project', comparison.before.projectName || '—'),
          Fact('Resolution', formatResolution(comparison.before.resolution)),
          Fact('Frame rate', describeFrameRate(comparison.before.frameRate)),
          Fact('Span', `${scale.span} frames`),
        ]),
        el('div', { class: 'page__timelines' }, [
          Timeline({ side: 'before', label: 'BEFORE', layout: before,
                     selectedRef: this.selectedRef, onSelect }),
          Timeline({ side: 'after', label: 'AFTER', layout: after,
                     selectedRef: this.selectedRef, onSelect }),
        ]),
        el('div', { class: 'page__lower' }, [
          ChangeList({
            changes: changes.map((change) => decorate(change, scale)),
            selected: this.selected,
            onSelect: (change) => onSelect(change, this.clipFor(change, changeIndex), sideOf(change)),
          }),
          ChangeInspector({
            change: this.selected,
            comparison,
            onClear: () => onSelect(null),
          }),
        ]),
      ]),
    ]);
  }

  /** The snapshot clip a change refers to, used to highlight both sides on selection. */
  clipFor(change, changeIndex) {
    const side = change.after || change.before;
    return { clipId: side.clipId, trackId: side.trackId };
  }
}

/** Adds the position of a change to the shared scale, for the list to show. */
function decorate(change, scale) {
  return { ...change, position: changePosition(change, scale) };
}

function sideOf(change) {
  return change.after ? 'after' : 'before';
}

function Fact(label, value) {
  return el('div', { class: 'fact', data: { label } }, [
    el('span', { class: 'fact__label', text: label }),
    el('span', { class: 'fact__value', text: value }),
  ]);
}

function formatResolution(resolution) {
  if (!resolution) return '—';
  return `${resolution.width} × ${resolution.height}`;
}

/** Loading, failure, and the shapes both of them are drawn with. */
export function Status({ kind, message, error }) {
  if (kind === 'loading') {
    return el('div', { class: 'status status--loading', role: 'status' }, [
      el('span', { class: 'status__spinner' }),
      el('p', { class: 'status__message', text: message }),
    ]);
  }
  const title = error ? error.title : 'Something went wrong';
  return el('div', { class: 'status status--error', role: 'alert' }, [
    el('h1', { class: 'status__title', text: title }),
    error && error.detail
      ? el('p', { class: 'status__message', text: error.detail })
      : null,
    el('p', { class: 'status__hint' },
      'Check that both commits exist, then try ',
      el('a', { href: '/commits', text: 'the commit list' }),
      '.'),
  ]);
}
