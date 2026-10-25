import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = {};
const utils = await import('./DashboardUtils.js');

test('topPlayers sorts by generated power and caps the list', () => {
  const players = [
    { name: 'slow', team: 1, generated: 10 },
    { name: 'fast', team: 2, generated: 90 },
    { name: 'middle', team: 1, generated: 50 },
  ];
  assert.deepEqual(utils.topPlayers(players, 2), [
    { name: 'fast', team: 2, generated: 90 },
    { name: 'middle', team: 1, generated: 50 },
  ]);
});

test('topPlayers breaks ties by name so the order is deterministic', () => {
  const players = [
    { name: 'bravo', generated: 50 },
    { name: 'alpha', generated: 50 },
  ];
  assert.deepEqual(utils.topPlayers(players, 10).map((p) => p.name), ['alpha', 'bravo']);
});

test('topPlayers never mutates the input array, which the live race state also uses', () => {
  const players = [
    { name: 'slow', generated: 10 },
    { name: 'fast', generated: 90 },
  ];
  utils.topPlayers(players, 1);
  assert.deepEqual(players.map((p) => p.name), ['slow', 'fast']);
});

test('topPlayers handles a count larger than the field', () => {
  const players = [{ name: 'only', generated: 1 }];
  assert.deepEqual(utils.topPlayers(players, 10), [{ name: 'only', generated: 1 }]);
});
