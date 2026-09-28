import { test, before } from 'node:test';
import assert from 'node:assert/strict';

import { installDom } from './dom.mjs';
import { comparison, snapshot, track, clip, change, occurrence, NTSC }
  from './fixture.mjs';

// The shim has to exist before any module that touches `document` is imported, and the
// components are imported lazily for that reason.
let Clip, TrackRow, Timeline, ChangeList, ChangeInspector, ComparisonHeader, Ruler;

before(async () => {
  installDom();
  ({ Clip } = await import('../components/Clip.js'));
  ({ Track: TrackRow } = await import('../components/Track.js'));
  ({ Timeline } = await import('../components/Timeline.js'));
  ({ ChangeList } = await import('../components/ChangeList.js'));
  ({ ChangeInspector } = await import('../components/ChangeInspector.js'));
  ({ ComparisonHeader } = await import('../components/ComparisonHeader.js'));
  ({ Ruler } = await import('../components/Ruler.js'));
});

/**
 * The rendered view, not the arithmetic behind it.
 *
 * The pure maths is covered in `layout.test.mjs`; these tests check that the components
 * actually put those numbers into the document, and mark the right clips. A component that
 * computed correctly and then drew the wrong thing would pass one set and fail the other.
 */

const positioned = (node) => ({
  left: Number.parseFloat(node.styleOf('left')),
  width: Number.parseFloat(node.styleOf('width')),
});

test('a clip is drawn at its position and size', () => {
  const node = Clip({
    clip: {
      clipId: 'clip_001', assetId: 'asset_001', assetName: 'interview.mp4',
      trackId: 'track_001', start: 250, duration: 500, sourceIn: 0, sourceOut: 499,
      left: 25, width: 50, exactWidth: 50, change: null,
    },
    rate: NTSC, side: 'before', selected: false, onSelect: () => {},
  });

  assert.equal(node.tagName, 'BUTTON', 'a clip is aimable, so it is a button');
  assert.deepEqual(positioned(node), { left: 25, width: 50 });
  assert.equal(node.dataset.side, 'before');
  assert.equal(node.dataset.changed, 'false');
  assert.match(node.textContent, /interview\.mp4/);
  assert.match(node.textContent, /250f/);
});

test('a changed clip is marked with the type the backend sent', () => {
  const trimmed = change({ type: 'TRIMMED', kinds: ['TRIMMED'] });
  const node = Clip({
    clip: {
      clipId: 'clip_001', assetId: 'asset_001', assetName: 'interview.mp4',
      trackId: 'track_001', start: 0, duration: 100, left: 0, width: 10, exactWidth: 10,
      change: trimmed,
    },
    rate: NTSC, side: 'after', selected: false, onSelect: () => {},
  });

  assert.equal(node.dataset.changed, 'true');
  assert.equal(node.dataset.kind, 'TRIMMED', 'the exact type, not a guess from the numbers');
  assert.ok(node.className.includes('clip--trimmed'), 'and a class the stylesheet can colour');
  assert.match(node.textContent, /TRIMMED/, 'the badge shows it without hovering');
});

test('a clip widened to stay clickable is marked as widened', () => {
  const node = Clip({
    clip: {
      clipId: 'clip_001', assetId: 'a', assetName: 'a.mp4', trackId: 'track_001',
      start: 0, duration: 3, left: 0, width: 3, exactWidth: 0.1, change: null,
    },
    rate: NTSC, side: 'before', selected: false, onSelect: () => {},
  });

  assert.equal(node.dataset.widened, 'true', 'so a short clip is not mistaken for a long one');
});

test('clicking a clip reports the change that was selected', () => {
  const moved = change({ type: 'MOVED' });
  let picked = null;
  const node = Clip({
    clip: {
      clipId: 'clip_001', assetId: 'a', assetName: 'a.mp4', trackId: 'track_001',
      start: 0, duration: 100, left: 0, width: 10, exactWidth: 10, change: moved,
    },
    rate: NTSC, side: 'after', selected: false,
    onSelect: (c) => { picked = c; },
  });

  node.click();
  assert.equal(picked, moved);
});

test('clicking an unchanged clip reports nothing to select', () => {
  let called = 0;
  const node = Clip({
    clip: {
      clipId: 'clip_001', assetId: 'a', assetName: 'a.mp4', trackId: 'track_001',
      start: 0, duration: 100, left: 0, width: 10, exactWidth: 10, change: null,
    },
    rate: NTSC, side: 'before', selected: false,
    onSelect: () => { called++; },
  });

  node.click();
  assert.equal(called, 1, 'the click still registers, so the inspector can be cleared');
});

test('a track renders its clips, and an empty one still renders', () => {
  const node = TrackRow({
    track: {
      id: 'track_004', type: 'VIDEO', index: 3, label: 'VIDEO 4',
      clips: [
        { clipId: 'c1', assetId: 'a', assetName: 'a.mp4', trackId: 'track_004', start: 0, duration: 10, left: 0, width: 1, exactWidth: 1, change: null },
        { clipId: 'c2', assetId: 'a', assetName: 'b.mp4', trackId: 'track_004', start: 10, duration: 10, left: 1, width: 1, exactWidth: 1, change: null },
      ],
    },
    rate: NTSC, side: 'after', selectedRef: null, onSelect: () => {},
  });

  assert.equal(node.dataset.trackId, 'track_004');
  assert.equal(node.dataset.empty, 'false');
  assert.equal(node.descendants('BUTTON').length, 2);
  assert.match(node.textContent, /VIDEO/);

  const empty = TrackRow({
    track: { id: 'track_002', type: 'AUDIO', index: 1, label: 'AUDIO 2', clips: [] },
    rate: NTSC, side: 'after', selectedRef: null, onSelect: () => {},
  });
  assert.equal(empty.dataset.empty, 'true', 'an empty track is still a track');
  assert.equal(empty.descendants('BUTTON').length, 0);
});

test('a timeline renders every track and every clip, positioned', () => {
  const node = Timeline({
    side: 'before',
    label: 'BEFORE',
    layout: {
      stateId: 'state_a',
      duration: 2900,
      frameRate: NTSC,
      tracks: [
        { id: 'track_001', type: 'AUDIO', index: 0, label: 'AUDIO 1',
          clips: [{ clipId: 'clip_001', assetId: 'a', assetName: 'm.mp3', trackId: 'track_001', start: 0, duration: 1004, left: 0, width: 34.6, exactWidth: 34.6, change: null }] },
        { id: 'track_002', type: 'AUDIO', index: 1, label: 'AUDIO 2', clips: [] },
        { id: 'track_003', type: 'VIDEO', index: 2, label: 'VIDEO 3', clips: [] },
        { id: 'track_004', type: 'VIDEO', index: 3, label: 'VIDEO 4',
          clips: [
            { clipId: 'clip_002', assetId: 'b', assetName: 'intro.mp4', trackId: 'track_004', start: 0, duration: 804, left: 0, width: 27.7, exactWidth: 27.7, change: null },
            { clipId: 'clip_003', assetId: 'c', assetName: 'interview.mp4', trackId: 'track_004', start: 804, duration: 257, left: 27.7, width: 8.9, exactWidth: 8.9, change: null },
          ] },
      ],
    },
    selectedRef: null,
    onSelect: () => {},
  });

  assert.equal(node.dataset.trackCount, '4');
  assert.equal(node.dataset.clipCount, '3', '5 placements in the sample, 3 drawn here');
  const clips = node.descendants('BUTTON');
  assert.equal(clips.length, 3);
  assert.deepEqual(positioned(clips[2]), { left: 27.7, width: 8.9 },
    'the interview sits a quarter of the way along, at 804 of 2900 frames');
  assert.match(node.textContent, /BEFORE/);
  assert.match(node.textContent, /2900 frames/);
  assert.match(node.textContent, /23\.976 fps/, 'the real rate, not a rounded 24');
});

test('the ruler is numbered in frames and in time', () => {
  const node = Ruler({ span: 2900, rate: NTSC });
  const ticks = node.descendants('DIV');

  assert.ok(ticks.length >= 3);
  assert.equal(ticks[0].dataset.frame, '0');
  assert.match(ticks[0].textContent, /00:00:00\.000/);
  assert.equal(ticks[ticks.length - 1].styleOf('left'), '100%', 'the last tick reaches the end');
});

test('a change list shows every change with the type the backend sent', () => {
  const changes = [
    change({ type: 'TRIMMED', kinds: ['TRIMMED'] }),
    change({ type: 'MOVED', kinds: ['MOVED', 'TRIMMED'] }),
    change({ type: 'ADDED', kinds: ['ADDED'], before: null, after: occurrence({ clipId: 'clip_009' }) }),
  ];
  const node = ChangeList({ changes, selected: null, onSelect: () => {} });
  const rows = node.byClass('change');

  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map((r) => r.dataset.type), ['TRIMMED', 'MOVED', 'ADDED']);
  assert.match(rows[1].textContent, /MOVED \+ TRIMMED/, 'both kinds are shown when there are two');
  assert.equal(node.byData('count', '3') !== null, true);
});

test('a comparison with no changes says so, and shows no rows', () => {
  const node = ChangeList({ changes: [], selected: null, onSelect: () => {} });

  assert.equal(node.byClass('change').length, 0);
  assert.match(node.textContent, /No structural changes/);
});

test('the inspector fills in from the two sides the backend sent', () => {
  const trimmed = change({
    type: 'TRIMMED',
    before: occurrence({ timelineStart: 804, timelineDuration: 800, sourceIn: 0, sourceOut: 799 }),
    after: occurrence({ timelineStart: 804, timelineDuration: 650, sourceIn: 0, sourceOut: 649 }),
  });
  const comp = comparison({ changes: [trimmed] });
  const node = ChangeInspector({ change: trimmed, comparison: comp, onClear: () => {} });

  assert.equal(node.dataset.type, 'TRIMMED');
  assert.match(node.textContent, /Trimmed/);
  assert.match(node.textContent, /800 f/);
  assert.match(node.textContent, /650 f/);
  assert.match(node.textContent, /0–799/);
  assert.match(node.textContent, /0–649/);
});

test('the inspector prompts when nothing is selected', () => {
  const node = ChangeInspector({ change: null, comparison: comparison(), onClear: () => {} });
  assert.match(node.textContent, /Pick a clip/);
  assert.equal(node.byClass('inspector__rows').length, 0);
});

test('an added clip shows a position without inventing an earlier one', () => {
  const added = change({ type: 'ADDED', before: null, after: occurrence() });
  const node = ChangeInspector({
    change: added, comparison: comparison({ changes: [added] }), onClear: () => {},
  });

  assert.match(node.textContent, /Added/);
  assert.match(node.textContent, /Position/);
  assert.equal(node.byClass('inspector__from').length, 0, 'nothing claims a from it does not have');
});

test('the header names both commits', () => {
  const node = ComparisonHeader({ comparison: comparison() });
  const ids = node.byClass('commit__id');

  assert.equal(ids.length, 2);
  assert.equal(ids[0].textContent, 'aaaaaaaa');
  assert.equal(ids[1].textContent, 'bbbbbbbb');
  assert.match(node.textContent, /first cut/);
  assert.match(node.textContent, /second cut/);
  assert.match(node.textContent, /editor@example\.com/);
});

test('a header without commits, for a comparison made from bare states, still renders', () => {
  const node = ComparisonHeader({ comparison: { from: null, to: null, before: snapshot(), after: snapshot() } });
  assert.match(node.textContent, /VVCS/);
  assert.equal(node.byClass('commit').length, 2);
});
