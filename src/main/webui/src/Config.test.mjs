import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = {};
const config = await import('./Config.js');

test('UI uses the teams returned by the server configuration', async () => {
  const teams = [
    { id: 1, name: 'Alpine', color: 'green', car: 'car-red' },
    { id: 2, name: 'Coast', color: 'blue', car: 'car-grey' },
  ];
  globalThis.fetch = async (url) => {
    assert.equal(url, '/api/config');
    return { ok: true, json: async () => ({ teams }) };
  };
  await config.loadConfig();
  assert.deepEqual(config.TEAMS_CONFIG, teams);
});

test('configuration load rejects HTTP failures and missing teams', async () => {
  globalThis.fetch = async () => ({ ok: false });
  await assert.rejects(config.loadConfig(), /Cannot load/);
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ teams: [] }) });
  await assert.rejects(config.loadConfig(), /Invalid race team/);
});

test('the input mode comes from the server configuration', async () => {
  const teams = [
    { id: 1, name: 'Alpine', color: 'green', car: 'car-red' },
    { id: 2, name: 'Coast', color: 'blue', car: 'car-grey' },
  ];
  for (const mode of ['tap', 'shake', 'both']) {
    globalThis.fetch = async () => ({ ok: true, json: async () => ({ teams, inputMode: mode }) });
    await config.loadConfig();
    assert.equal(config.INPUT_MODE, mode);
  }
});

test('a missing or unknown input mode falls back to tap', async () => {
  const teams = [
    { id: 1, name: 'Alpine', color: 'green', car: 'car-red' },
    { id: 2, name: 'Coast', color: 'blue', car: 'car-grey' },
  ];
  for (const inputMode of [undefined, null, '', 'blow', 'toString', 42]) {
    globalThis.fetch = async () => ({ ok: true, json: async () => ({ teams, inputMode: 'shake' }) });
    await config.loadConfig();
    globalThis.fetch = async () => ({ ok: true, json: async () => ({ teams, inputMode }) });
    await config.loadConfig();
    assert.equal(config.INPUT_MODE, 'tap');
  }
});

