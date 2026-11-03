import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { protocol: 'http:', host: 'localhost:8080' } };
globalThis.sessionStorage = { getItem() { return null; }, setItem() {} };
globalThis.localStorage = { getItem() { return null; }, setItem() {} };

const { applyGameEvent } = await import('./GameApi.js');

test('applyGameEvent updates status on start', () => {
  let status;
  applyGameEvent({ type: 'start' }, (s) => { status = s; });
  assert.deepEqual(status, { value: 'started' });
});

test('applyGameEvent pauses status and invokes reset callback on reset', () => {
  let status;
  let resetCalled = false;
  applyGameEvent({ type: 'reset' }, (s) => { status = s; }, () => { resetCalled = true; });
  assert.deepEqual(status, { value: 'paused' });
  assert.equal(resetCalled, true);
});

test('applyGameEvent updates status and attaches data on finish', () => {
  let status;
  const finishData = { winner: 1, elapsed: 12345 };
  applyGameEvent({ type: 'finish', data: finishData }, (s) => { status = s; });
  assert.deepEqual(status, { value: 'finished', data: finishData });
});

test('applyGameEvent pauses on pause or empty', () => {
  let status;
  applyGameEvent({ type: 'pause' }, (s) => { status = s; });
  assert.deepEqual(status, { value: 'paused' });

  status = undefined;
  applyGameEvent({ type: 'empty' }, (s) => { status = s; });
  assert.deepEqual(status, { value: 'paused' });
});

test('applyGameEvent safely handles theme and input-mode events without altering status', () => {
  let statusCalled = false;
  applyGameEvent({ type: 'theme', data: {} }, () => { statusCalled = true; });
  applyGameEvent({ type: 'input-mode', data: { mode: 'shake' } }, () => { statusCalled = true; });
  assert.equal(statusCalled, false);
});
