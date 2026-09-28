import { el } from '../lib/dom.js';

/**
 * Which two commits are being compared.
 *
 * Author and timestamp are shown when the backend sent them and left out when it did not,
 * rather than printed as a dash. An empty field reads as missing information; a field that was
 * never there should not look like one that failed to load.
 */
export function ComparisonHeader({ comparison }) {
  return el('header', { class: 'header' }, [
    el('div', { class: 'header__brand' }, [
      el('span', { class: 'header__logo', text: 'VVCS' }),
      el('span', { class: 'header__tagline', text: 'timeline comparison' }),
    ]),
    el('div', { class: 'header__pair' }, [
      CommitCard({ commit: comparison.from, caption: 'A' }),
      el('span', { class: 'header__arrow', text: '→' }),
      CommitCard({ commit: comparison.to, caption: 'B' }),
    ]),
  ]);
}

function CommitCard({ commit, caption }) {
  if (!commit) return el('div', { class: 'commit commit--unknown' }, [
    el('span', { class: 'commit__caption', text: caption }),
    el('span', { class: 'commit__message', text: 'not from a repository' }),
  ]);
  const meta = [commit.author, commit.timestamp].filter(Boolean);
  return el('div', { class: 'commit', data: { shortId: commit.shortId || '' } }, [
    el('span', { class: 'commit__caption', text: caption }),
    el('code', { class: 'commit__id', text: commit.shortId || '—' }),
    el('span', { class: 'commit__message', text: commit.message || '(no message)' }),
    meta.length ? el('span', { class: 'commit__meta', text: meta.join(' · ') }) : null,
  ]);
}
