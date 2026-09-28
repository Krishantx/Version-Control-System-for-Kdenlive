import { test } from 'node:test';
import assert from 'node:assert/strict';

import { computeScale, layoutTimeline, rulerTicks, trackLabel, MIN_CLIP_PX }
  from '../lib/layout.js';
import { indexChanges, describeChange, inspectorRows, labelFor, symbolFor }
  from '../lib/changes.js';
import { snapshot, comparison, change, occurrence, clip, track, NTSC, THIRTY }
  from './fixture.mjs';

/**
 * The positioning arithmetic and the change joining, which are the two places a timeline
 * viewer can be quietly and completely wrong.
 */

test('a clip is placed at its start as a share of the timeline', () => {
  const scale = computeScale(snapshot(), null, 100);
  const layout = layoutTimeline(
    snapshot({ tracks: [track({ clips: [clip({ start: 250, duration: 500 })] })] }),
    scale, null, 'before',
  );
  const block = layout.tracks[0].clips[0];

  assert.equal(block.left, 25, '250 of 1000 frames is a quarter of the way along');
  assert.equal(block.width, 50, '500 of 1000 frames is half the width');
});

test('positions are exact, not rounded to whole pixels', () => {
  // 1 frame of 2900 is 0.034% of the width; rounding it away would put short clips in the
  // wrong place on a long timeline.
  const scale = computeScale(snapshot({ duration: 2900 }), null, 1200);
  assert.ok(Math.abs(scale.toPx(1) - 1200 / 2900) < 1e-9);
  assert.equal(scale.span, 2900);
});

test('a clip keeps a minimum width so it can be pointed at', () => {
  // A 3-frame clip on a 2900-frame timeline is 0.1% of the width, which is under a pixel.
  const tiny = clip({ start: 0, duration: 3 });
  const scale = computeScale(snapshot({ duration: 2900 }), null, 1200);
  const block = layoutTimeline(
    snapshot({ duration: 2900, tracks: [track({ clips: [tiny] })] }),
    scale, null, 'before',
  ).tracks[0].clips[0];

  assert.ok(block.width >= MIN_CLIP_PX, 'the drawn clip is still aimable');
  assert.ok(block.exactWidth < MIN_CLIP_PX, 'and its true width is remembered, so the two can be told apart');
  assert.notEqual(block.width, block.exactWidth, 'a widened clip is distinguishable from a longer one');
});

test('before and after share one scale, so a timeline that only grew is not drawn as unchanged', () => {
  const scale = computeScale(snapshot({ duration: 1000 }), snapshot({ duration: 1200 }), 100);

  assert.equal(scale.span, 1200, 'the scale spans the longer of the two');
  const before = layoutTimeline(snapshot({ duration: 1000 }), scale, null, 'before');
  assert.ok(before.tracks[0].clips[0].left < 30, 'the earlier clip does not reach the end of the after');
});

test('a zero-length timeline does not divide by zero', () => {
  const scale = computeScale(snapshot({ duration: 0 }), null, 100);
  assert.equal(scale.span, 1);
  assert.ok(Number.isFinite(scale.toPx(0)));
});

test('every track is laid out, including one with no clips', () => {
  const many = snapshot({
    tracks: [
      track({ id: 'track_001', type: 'VIDEO', index: 0 }),
      track({ id: 'track_002', type: 'VIDEO', index: 1, clips: [clip()] }),
      track({ id: 'track_003', type: 'VIDEO', index: 2, clips: [] }),
      track({ id: 'track_004', type: 'AUDIO', index: 3, clips: [clip({ clipId: 'clip_009' })] }),
    ],
  });
  const layout = layoutTimeline(many, computeScale(many, null, 100), null, 'before');

  assert.equal(layout.tracks.length, 4, 'an empty track is still a track');
  assert.equal(layout.tracks[2].clips.length, 0, 'and it has no clips');
  assert.deepEqual(layout.tracks.map((t) => t.label), [
    'VIDEO 1', 'VIDEO 2', 'VIDEO 3', 'AUDIO 4',
  ]);
});

test('a track label is its type and position, numbered as an editor would', () => {
  assert.equal(trackLabel({ type: 'VIDEO', index: 0 }), 'VIDEO 1');
  assert.equal(trackLabel({ type: 'AUDIO', index: 2 }), 'AUDIO 3');
});

test('ruler ticks are round numbers, evenly spaced, and always end at the span', () => {
  const ticks = rulerTicks(2900, 8);

  assert.ok(ticks.length >= 3 && ticks.length <= 15, 'a readable number of ticks');
  assert.equal(ticks[0].frame, 0, 'the ruler starts at zero');
  assert.equal(ticks[ticks.length - 1].frame, 2900, 'and ends at the end of the timeline, so the last block has an edge to reach');

  // Interior ticks are evenly spaced; only the final gap may be shorter, because the span is
  // not a whole number of intervals.
  const interior = ticks.slice(1, -1).map((t, i) => t.frame - (i === 0 ? 0 : ticks[i].frame));
  assert.equal(new Set(interior).size, 1, 'one interval throughout the body of the ruler');
  const step = interior[0];
  assert.ok([1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000].includes(step)
    || Number.isInteger(Math.log10(step)), 'the interval is 1, 2 or 5 times a power of ten');
  assert.ok(ticks.every((t) => t.left >= 0 && t.left <= 100), 'every tick is on the timeline');
  assert.equal(ticks[ticks.length - 1].left, 100, 'the last tick sits at the right edge');
});

test('a ruler over an empty timeline has no ticks rather than throwing', () => {
  assert.deepEqual(rulerTicks(0), []);
  assert.deepEqual(rulerTicks(-5), []);
});

test('a change is joined to its clip on the side it belongs to', () => {
  const trimmed = change({
    type: 'TRIMMED',
    kinds: ['TRIMMED'],
    before: occurrence({ timelineStart: 100, timelineDuration: 200 }),
    after: occurrence({ timelineStart: 100, timelineDuration: 120 }),
  });
  const index = indexChanges([trimmed]);
  const moved = clip({ clipId: 'clip_001', start: 100, duration: 120 });

  assert.equal(index.get('before', 'clip_001', 'track_001'), trimmed);
  assert.equal(index.get('after', 'clip_001', 'track_001'), trimmed);
  assert.equal(index.get('before', 'clip_001', 'other_track'), null, 'the track is part of the identity');
  assert.equal(index.get('before', 'clip_999', 'track_001'), null, 'an unrelated clip is not changed');
  assert.ok(moved);
});

test('a clip that changed track is marked on the track it left and the one it joined', () => {
  // This is the case a naive join gets wrong: the same clip, two tracks, and the viewer has
  // to show the move on both timelines or it is only telling half the story.
  const across = change({
    type: 'TRACK_CHANGED',
    kinds: ['TRACK_CHANGED'],
    before: occurrence({ trackId: 'track_001' }),
    after: occurrence({ trackId: 'track_002' }),
  });
  const index = indexChanges([across]);

  assert.equal(index.get('before', 'clip_001', 'track_001'), across);
  assert.equal(index.get('after', 'clip_001', 'track_002'), across);
  assert.equal(index.get('after', 'clip_001', 'track_001'), null);
  assert.equal(index.before.size, 1);
  assert.equal(index.after.size, 1);
});

test('an added clip is marked only where it now exists', () => {
  const added = change({ type: 'ADDED', kinds: ['ADDED'], before: null, after: occurrence() });
  const index = indexChanges([added]);

  assert.equal(index.get('after', 'clip_001', 'track_001'), added);
  assert.equal(index.get('before', 'clip_001', 'track_001'), null);
});

test('a removed clip is marked only where it used to be', () => {
  const removed = change({ type: 'REMOVED', kinds: ['REMOVED'], before: occurrence(), after: null });
  const index = indexChanges([removed]);

  assert.equal(index.get('before', 'clip_001', 'track_001'), removed);
  assert.equal(index.get('after', 'clip_001', 'track_001'), null);
});

test('changed clips are marked in the layout, and unchanged ones are not', () => {
  const index = indexChanges([change({
    before: occurrence({ clipId: 'clip_002' }),
    after: occurrence({ clipId: 'clip_002', timelineStart: 300 }),
  })]);
  const scale = computeScale(snapshot(), null, 100);
  const layout = layoutTimeline(snapshot({
    tracks: [track({
      clips: [clip({ clipId: 'clip_001' }), clip({ clipId: 'clip_002', start: 300 })],
    })],
  }), scale, index, 'before');

  assert.equal(layout.tracks[0].clips[0].change, null, 'an unchanged clip is not marked');
  assert.equal(layout.tracks[0].clips[1].change.type, 'MOVED', 'the changed one is');
});

test('the frontend reports the type the backend sent, and never works one out', () => {
  // A trim and a move that the backend reported together must stay together, and a clip whose
  // numbers look different for some other reason must not be relabelled.
  const both = change({ type: 'MOVED', kinds: ['MOVED', 'TRIMMED'] });
  const described = describeChange(both);

  assert.equal(described.type, 'MOVED');
  assert.deepEqual(described.kinds, ['MOVED', 'TRIMMED']);
  assert.equal(described.label, 'Moved');
});

test('every type the backend can send has a word and a symbol', () => {
  const types = ['ADDED', 'REMOVED', 'MOVED', 'TRIMMED', 'REORDERED', 'TRACK_CHANGED', 'MODIFIED'];
  for (const type of types) {
    assert.ok(labelFor(type).length > 0, `${type} needs a word`);
    assert.ok(symbolFor(type).length > 0, `${type} needs a symbol`);
    assert.equal(describeChange(change({ type, kinds: [type] })).label, labelFor(type),
      `${type} is labelled the same way through describeChange`);
  }
});

test('a type this frontend has never heard of is still shown, not hidden', () => {
  assert.equal(labelFor('SOMETHING_NEW'), 'SOMETHING_NEW');
  assert.equal(symbolFor('SOMETHING_NEW'), '•');
});

test('the inspector shows only the rows that have something to say', () => {
  const moved = change({
    type: 'MOVED',
    before: occurrence({ timelineStart: 100, timelineDuration: 200 }),
    after: occurrence({ timelineStart: 400, timelineDuration: 200 }),
  });
  const rows = inspectorRows(moved, { before: snapshot(), after: snapshot() });
  const labels = rows.map((r) => r.label);

  assert.ok(labels.includes('Position'), 'a move has a position');
  assert.ok(!labels.includes('Duration'), 'but the duration did not change, so no row for it');
  assert.ok(!labels.includes('Track'), 'and it stayed on its track');
  assert.deepEqual(rows.find((r) => r.label === 'Position'), {
    label: 'Position', from: '100 f', to: '400 f',
  });
});

test('an added clip has a position but no earlier one', () => {
  const added = change({ type: 'ADDED', before: null, after: occurrence() });
  const rows = inspectorRows(added, { before: snapshot(), after: snapshot() });
  const labels = rows.map((r) => r.label);

  assert.ok(labels.includes('Position'));
  assert.ok(labels.includes('Duration'));
  assert.ok(labels.includes('Media'));
  assert.ok(rows.every((r) => r.value !== undefined), 'nothing claims a from it does not have');
});

test('a trim reports both the length and the source range that changed', () => {
  const trimmed = change({
    type: 'TRIMMED',
    before: occurrence({ timelineDuration: 800, sourceIn: 0, sourceOut: 799 }),
    after: occurrence({ timelineDuration: 650, sourceIn: 0, sourceOut: 649 }),
  });
  const labels = inspectorRows(trimmed, { before: snapshot(), after: snapshot() })
    .map((r) => r.label);

  assert.ok(labels.includes('Duration'));
  assert.ok(labels.includes('Source range'));
});

test('a change that crossed tracks is reported as a track change, with both names', () => {
  const across = change({
    type: 'TRACK_CHANGED',
    before: occurrence({ trackId: 'track_001' }),
    after: occurrence({ trackId: 'track_002' }),
  });
  const before = snapshot({ tracks: [track({ id: 'track_001' })] });
  const after = snapshot({ tracks: [track({ id: 'track_002' })] });
  const beforeSnap = snapshot({ tracks: [track({ id: 'track_001', type: 'VIDEO', index: 0 })] });
  const afterSnap = snapshot({ tracks: [track({ id: 'track_002', type: 'VIDEO', index: 1 })] });
  const row = inspectorRows(across, { before: beforeSnap, after: afterSnap })
    .find((r) => r.label === 'Track');

  assert.deepEqual(row, { label: 'Track', from: 'VIDEO 1', to: 'VIDEO 2' });
});

test('no changes means the index is empty and nothing is marked', () => {
  const index = indexChanges([]);
  assert.equal(index.get('before', 'clip_001', 'track_001'), null);
  assert.equal(index.before.size, 0);
});

test('an empty change list does not break the layout', () => {
  const index = indexChanges(undefined);
  const layout = layoutTimeline(snapshot(), computeScale(snapshot(), null, 100), index, 'before');
  assert.equal(layout.tracks[0].clips[0].change, null);
});

test('a real comparison from the sample project lays out the tracks it has', () => {
  // The shape the API actually sends for the sample: four tracks, one of them empty.
  const real = snapshot({
    duration: 2900,
    tracks: [
      track({ id: 'track_001', type: 'AUDIO', index: 0, clips: [clip({ clipId: 'clip_001', start: 0, duration: 1004 })] }),
      track({ id: 'track_002', type: 'AUDIO', index: 1, clips: [] }),
      track({ id: 'track_003', type: 'VIDEO', index: 2, clips: [] }),
      track({ id: 'track_004', type: 'VIDEO', index: 3, clips: [
        clip({ clipId: 'clip_002', start: 0, duration: 804 }),
        clip({ clipId: 'clip_003', start: 804, duration: 257 }),
        clip({ clipId: 'clip_004', start: 1061, duration: 536 }),
        clip({ clipId: 'clip_005', start: 1597, duration: 1089 }),
      ] }),
    ],
  });
  const layout = layoutTimeline(real, computeScale(real, null, 100), null, 'after');
  const video = layout.tracks[3];

  assert.equal(layout.tracks.length, 4);
  assert.deepEqual(layout.tracks.map((t) => t.clips.length), [1, 0, 0, 4]);
  // The second video clip starts where the first ends, to the frame.
  assert.equal(video.clips[0].left + video.clips[0].width, video.clips[1].left);
  assert.equal(video.clips[1].left, (804 / 2900) * 100);
});
