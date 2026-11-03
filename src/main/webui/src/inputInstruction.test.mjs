import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = {};
const { inputInstruction } = await import('./inputInstruction.js');
const { inputsForMode, normalizeInputMode } = await import('./inputMode.js');

test('each mode names its own actions', () => {
  assert.equal(inputInstruction('tap'), 'Tap the windmill now');
  assert.equal(inputInstruction('shake'), 'Shake your phone now');
  assert.equal(inputInstruction('both'), 'Tap the windmill or shake your phone now');
});

test('an unknown or missing mode acts as tap', () => {
  assert.equal(inputInstruction(), 'Tap the windmill now');
  assert.equal(inputInstruction('blow'), 'Tap the windmill now');
});

test('blow and swipe still come from the extra inputs', () => {
  assert.equal(inputInstruction('tap', { blow: true }), 'Tap the windmill or blow into your phone now');
  assert.equal(inputInstruction('shake', { swipe: true }), 'Shake your phone or swipe the screen now');
  assert.equal(
    inputInstruction('both', { blow: true }),
    'Tap the windmill, shake your phone or blow into your phone now',
  );
});

test('with nothing enabled it still says go', () => {
  assert.equal(inputInstruction('tap', { tap: false }), 'Go');
});

test('the default extras follow Config.js, where blowing and swiping are off', () => {
  assert.equal(inputInstruction('both'), 'Tap the windmill or shake your phone now');
});

test('inputsForMode maps a mode to the tap and shake switches', () => {
  assert.deepEqual(inputsForMode('tap'), { tap: true, shake: false });
  assert.deepEqual(inputsForMode('shake'), { tap: false, shake: true });
  assert.deepEqual(inputsForMode('both'), { tap: true, shake: true });
  assert.deepEqual(inputsForMode('nonsense'), { tap: true, shake: false });
});

test('normalizeInputMode keeps known modes and defaults the rest to tap', () => {
  assert.equal(normalizeInputMode('shake'), 'shake');
  assert.equal(normalizeInputMode(undefined), 'tap');
  assert.equal(normalizeInputMode('constructor'), 'tap');
});
