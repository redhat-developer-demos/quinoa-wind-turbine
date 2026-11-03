import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_INPUT_MODE,
  normalizeInputMode,
  inputsForMode,
  isDeviceMotionSupported,
  effectiveInputs,
} from './inputMode.js';

test('DEFAULT_INPUT_MODE is tap', () => {
  assert.equal(DEFAULT_INPUT_MODE, 'tap');
});

test('normalizeInputMode normalizes valid and invalid modes', () => {
  assert.equal(normalizeInputMode('tap'), 'tap');
  assert.equal(normalizeInputMode('shake'), 'shake');
  assert.equal(normalizeInputMode('both'), 'both');
  assert.equal(normalizeInputMode(''), 'tap');
  assert.equal(normalizeInputMode('unknown'), 'tap');
  assert.equal(normalizeInputMode(null), 'tap');
  assert.equal(normalizeInputMode(undefined), 'tap');
  assert.equal(normalizeInputMode('toString'), 'tap');
});

test('inputsForMode returns tap/shake map', () => {
  assert.deepEqual(inputsForMode('tap'), { tap: true, shake: false });
  assert.deepEqual(inputsForMode('shake'), { tap: false, shake: true });
  assert.deepEqual(inputsForMode('both'), { tap: true, shake: true });
  assert.deepEqual(inputsForMode('invalid'), { tap: true, shake: false });
});

test('isDeviceMotionSupported detects DeviceMotionEvent availability', () => {
  assert.equal(isDeviceMotionSupported({ DeviceMotionEvent: class {} }), true);
  assert.equal(isDeviceMotionSupported({ DeviceMotionEvent: undefined }), false);
  assert.equal(isDeviceMotionSupported({}), false);
  assert.equal(isDeviceMotionSupported(undefined), false);
});

test('effectiveInputs with motion support enabled', () => {
  assert.deepEqual(effectiveInputs('tap', true), { tap: true, shake: false });
  assert.deepEqual(effectiveInputs('shake', true), { tap: false, shake: true });
  assert.deepEqual(effectiveInputs('both', true), { tap: true, shake: true });
  assert.deepEqual(effectiveInputs('unknown', true), { tap: true, shake: false });
});

test('effectiveInputs fallback when motion support is unavailable', () => {
  // If DeviceMotionEvent is unavailable, tapping works in any mode and shaking is disabled
  assert.deepEqual(effectiveInputs('tap', false), { tap: true, shake: false });
  assert.deepEqual(effectiveInputs('shake', false), { tap: true, shake: false });
  assert.deepEqual(effectiveInputs('both', false), { tap: true, shake: false });
  assert.deepEqual(effectiveInputs('unknown', false), { tap: true, shake: false });
});
