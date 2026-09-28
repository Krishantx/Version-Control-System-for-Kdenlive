import { el, render } from '../lib/dom.js';
import { fetchCommits, fetchDiff } from '../api/diff.js';
import { Status } from './DiffPage.js';

/**
 * The entry screen: pick two commits.
 *
 * The server knows the history, so asking it is more reliable than typing an id and hoping,
 * and it saves a reader from having to open a terminal to find out what to type. A row is a
 * link, so a chosen pair can still be copied out of the address bar.
 */
export class CommitsPage {
  constructor(root, onCompare) {
    this.root = root;
    this.onCompare = onCompare;
    this.commits = [];
  }

  async load() {
    render(this.root, Status({ kind: 'loading', message: 'Loading commits…' }));
    try {
      this.commits = await fetchCommits();
      this.draw();
    } catch (error) {
      render(this.root, Status({ kind: 'error', error }));
    }
  }

  draw() {
    if (!this.commits.length) {
      render(this.root, [
        el('header', { class: 'header' }, [
          el('div', { class: 'header__brand' }, [
            el('span', { class: 'header__logo', text: 'VVCS' }),
            el('span', { class: 'header__tagline', text: 'timeline comparison' }),
          ]),
        ]),
        Status({ kind: 'error', error: {
          title: 'No commits yet',
          detail: 'Run vvcs commit at least twice to have something to compare.',
        } }),
      ]);
      return;
    }

    const [older, newer] = this.commits.length > 1
      ? [this.commits[1], this.commits[0]]
      : [this.commits[0], null];

    render(this.root, [
      el('header', { class: 'header' }, [
        el('div', { class: 'header__brand' }, [
          el('span', { class: 'header__logo', text: 'VVCS' }),
          el('span', { class: 'header__tagline', text: 'timeline comparison' }),
        ]),
      ]),
      el('main', { class: 'page' }, [
        el('section', { class: 'commits' }, [
          el('h1', { class: 'commits__title', text: 'Choose two commits' }),
          el('p', { class: 'commits__hint' }, [
            newer && older
              ? 'Newest first. The most recent two are pre-selected below.'
              : 'One commit is not a comparison; commit again to have two.',
          ]),
          el('ul', { class: 'commits__list', data: { count: String(this.commits.length) } },
            this.commits.map((commit) => CommitRow({ commit })),
          ),
          newer && older
            ? el('button', {
                class: 'commits__compare',
                type: 'button',
                text: `Compare ${older.shortId} → ${newer.shortId}`,
                onclick: () => this.onCompare(older.shortId, newer.shortId),
              })
            : null,
        ]),
      ]),
    ]);
  }
}

function CommitRow({ commit }) {
  return el('li', { class: 'commit-row', data: { shortId: commit.shortId } }, [
    el('code', { class: 'commit-row__id', text: commit.shortId }),
    el('span', { class: 'commit-row__message', text: commit.message || '(no message)' }),
    el('span', { class: 'commit-row__meta', text: [commit.author, commit.timestamp]
      .filter(Boolean).join(' · ') }),
  ]);
}
