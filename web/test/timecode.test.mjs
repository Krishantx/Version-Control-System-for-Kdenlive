import { test } from 'node:test';
import assert from 'node:assert/strict';

import { timecode, frames, nominalFps, describeFrameRate, shortDuration }
  from '../lib/timecode.js';
import { NTSC, THIRTY } from './fixture.mjs';

const FILM = { numerator: 24, denominator: 1 };
const TWENTY_FIVE = { numerator: 25, denominator: 1 };

/**
 * The same cases as `TimecodeTest` on the server.
 *
 * A frame shown as one timecode by the command line and another by the browser is a
 * discrepancy nobody notices until someone trusts a number, so these are the same assertions
 * in both places on purpose.
 */

test('whole rates render zero-padded HH:MM:SS.mmm', () => {
  assert.equal(timecode(0, THIRTY), '00:00:00.000');
  assert.equal(timecode(30, THIRTY), '00:00:01.000');
  assert.equal(timecode(1800, THIRTY), '00:01:00.000');
  // 900 frames at 25fps is 36 seconds, not 30
  assert.equal(timecode(900, TWENTY_FIVE), '00:00:36.000');
  // An hour is 108000 frames at 30fps
  assert.equal(timecode(108000, THIRTY), '01:00:00.000');
});

test('the same frame count is a different time at a different rate', () => {
  assert.equal(timecode(24, FILM), '00:00:01.000');
  assert.equal(timecode(25, TWENTY_FIVE), '00:00:01.000');
  assert.equal(timecode(30, THIRTY), '00:00:01.000');
  assert.equal(timecode(30, TWENTY_FIVE), '00:00:01.200');
  assert.equal(timecode(25, THIRTY), '00:00:00.833');
});

test('a fractional rate is divided through exactly', () => {
  // 24 frames is one second at 24fps, and a thousandth more at 23.976
  assert.equal(timecode(24, NTSC), '00:00:01.001');
  assert.equal(timecode(24, FILM), '00:00:01.000');
  // After 24000 frames the difference has grown to a whole second
  assert.equal(timecode(24000, NTSC), '00:16:41.000');
  assert.equal(timecode(24000, FILM), '00:16:40.000');
});

test('milliseconds never round up into the next second', () => {
  for (let frame = 0; frame < 3000; frame++) {
    const rendered = timecode(frame, NTSC);
    const millis = Number(rendered.slice(-3));
    assert.ok(millis >= 0 && millis < 1000, `bad milliseconds: ${rendered}`);
  }
});

test('the frame form reports frames within the second, consistently', () => {
  assert.equal(frames(0, THIRTY), '00:00:00:00');
  assert.equal(frames(30, THIRTY), '00:00:01:00');
  // 45 frames is one and a half seconds, so the seconds must read 1 beside the frame field
  assert.equal(frames(45, THIRTY), '00:00:01:15');
  assert.equal(frames(1800, THIRTY), '00:01:00:00');
  assert.equal(frames(108000, THIRTY), '01:00:00:00');
});

test('a fractional rate is treated as 24 for the frame field', () => {
  assert.equal(nominalFps(NTSC), 24);
  assert.equal(nominalFps(TWENTY_FIVE), 25);
  // The documented cost: at 23.976 the frame form counts seconds at 24fps
  assert.equal(frames(24000, NTSC), '00:16:40:00');
  assert.equal(timecode(24000, NTSC), '00:16:41.000');
});

test('a zero rate does not divide by zero', () => {
  const zero = { numerator: 0, denominator: 1 };
  assert.equal(timecode(100, zero), '00:00:00.000');
  assert.equal(nominalFps(zero), 1);
  assert.equal(timecode(100, null), '00:00:00.000');
});

test('the frame rate is described from the rational, not from a rounded number', () => {
  assert.equal(describeFrameRate(THIRTY), '30 fps');
  assert.equal(describeFrameRate(FILM), '24 fps');
  assert.equal(describeFrameRate(TWENTY_FIVE), '25 fps');
  // 24000/1001 is 23.976, and saying 24 would be a small lie in the one place a reader
  // might copy a number from
  assert.equal(describeFrameRate(NTSC), '23.976 fps');
  assert.equal(describeFrameRate(null), 'unknown rate');
});

test('a clip duration is shortened for a block that has little room', () => {
  assert.equal(shortDuration(30, THIRTY), '1.0s');
  assert.equal(shortDuration(900, TWENTY_FIVE), '36.0s');
  assert.equal(shortDuration(1800, THIRTY), '1m 0s');
  // At 23.976 a "minute" of frames is a shade under it
  assert.equal(shortDuration(1440, NTSC), '1m 0s');
});
