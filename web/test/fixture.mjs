/**
 * A comparison, shaped exactly like the API's response.
 *
 * Hand-written rather than captured, so that each test states the situation it is about
 * rather than asserting against a fixture that hides it. The numbers match the real sample
 * project's 24000/1001 rate and its 2900 frame sequence.
 */

export const NTSC = { numerator: 24000, denominator: 1001 };
export const THIRTY = { numerator: 30, denominator: 1 };

export const clip = (over = {}) => ({
  clipId: 'clip_001',
  assetId: 'asset_001',
  assetName: 'interview.mp4',
  trackId: 'track_001',
  start: 0,
  duration: 100,
  sourceIn: 0,
  sourceOut: 99,
  sourceFrameRate: THIRTY,
  ...over,
});

export const track = (over = {}) => ({
  id: 'track_001',
  type: 'VIDEO',
  index: 0,
  clips: [clip()],
  ...over,
});

export const snapshot = (over = {}) => ({
  stateId: 'state_before_0000',
  projectName: 'demo',
  id: 'timeline_1',
  name: 'Main',
  duration: 1000,
  frameRate: NTSC,
  resolution: { width: 1920, height: 1080 },
  tracks: [track()],
  ...over,
});

export const occurrence = (over = {}) => ({
  clipId: 'clip_001',
  key: 'e:1',
  assetId: 'asset_001',
  trackId: 'track_001',
  timelineStart: 0,
  timelineDuration: 100,
  sourceIn: 0,
  sourceOut: 99,
  orderOnTrack: 0,
  ...over,
});

export const change = (over = {}) => ({
  key: 'e:1',
  type: 'MOVED',
  kinds: ['MOVED'],
  before: occurrence(),
  after: occurrence(),
  ...over,
});

/** A comparison with the given changes, and optional snapshot overrides. */
export const comparison = ({ changes = [], before = {}, after = {} } = {}) => ({
  from: {
    id: 'a'.repeat(64),
    shortId: 'aaaaaaaa',
    stateId: 'state_before_0000',
    author: 'editor@example.com',
    message: 'first cut',
    timestamp: '2026-01-01T10:00:00Z',
  },
  to: {
    id: 'b'.repeat(64),
    shortId: 'bbbbbbbb',
    stateId: 'state_after_00000',
    author: 'editor@example.com',
    message: 'second cut',
    timestamp: '2026-01-02T10:00:00Z',
  },
  before: snapshot({ stateId: 'state_before_0000', ...before }),
  after: snapshot({ stateId: 'state_after_00000', ...after }),
  changes: {
    fromStateId: 'state_before_0000',
    toStateId: 'state_after_00000',
    assetChanges: [],
    trackChanges: [],
    clipChanges: changes,
    fromDuration: before.duration || 1000,
    toDuration: after.duration || 1000,
  },
});
