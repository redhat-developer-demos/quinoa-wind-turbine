import test from 'node:test';
import assert from 'node:assert/strict';
import { consume } from './RaceApi.js';

class Socket {
  static last;
  constructor() { Socket.last = this; }
  close() {}
  receive(message) { this.onmessage({ data: JSON.stringify(message) }); }
}

function fixture() {
  globalThis.WebSocket = Socket;
  globalThis.window = { location: { protocol: 'http:', host: 'localhost:8080' } };
  const lines = [];
  const close = consume(() => {}, () => {}, (line) => lines.push(line));
  return { lines, socket: Socket.last, close };
}
const race = (status, updatedAt, raceId = 'a') => ({ type: 'race', race: { status, updatedAt, raceId } });
const line = (id, snapshotAt, raceId = 'a', trigger = 'lead') => ({
  type: 'commentary', commentary: { id, snapshotAt, raceId, trigger, text: 'Team 1 takes the lead!' },
});

test('commentary ahead of start and resume waits for a running snapshot', () => {
  const f = fixture();
  f.socket.receive(race('WAITING', 1));
  f.socket.receive(line('first', 3));
  assert.equal(f.lines.at(-1), undefined);
  f.socket.receive(race('STARTED', 2));
  assert.equal(f.lines.at(-1).id, 'first');
  f.socket.receive(race('PAUSED', 4));
  f.socket.receive(line('resume', 6));
  assert.equal(f.lines.at(-1).id, 'first');
  f.socket.receive(race('STARTED', 5));
  assert.equal(f.lines.at(-1).id, 'resume');
  f.close();
});

test('cached lines can precede the initial snapshot and duplicates are ignored', () => {
  const f = fixture();
  f.socket.receive(line('cached', 10));
  assert.equal(f.lines.length, 0);
  f.socket.receive(race('STARTED', 11));
  assert.equal(f.lines.at(-1).id, 'cached');
  const count = f.lines.length;
  f.socket.receive(line('cached', 10));
  f.socket.receive(line('older', 9));
  assert.equal(f.lines.length, count);
  f.close();
});

test('reset clears commentary and ignores previous-race lines', () => {
  const f = fixture();
  f.socket.receive(race('STARTED', 1));
  f.socket.receive(line('first', 2));
  f.socket.receive({ type: 'game', event: { type: 'reset' } });
  assert.equal(f.lines.at(-1), undefined);
  f.socket.receive(race('WAITING', 3, 'b'));
  f.socket.receive(line('stale', 4));
  f.socket.receive(race('STARTED', 5, 'b'));
  assert.equal(f.lines.at(-1), undefined);
  f.socket.receive(line('new-race', 6, 'b'));
  assert.equal(f.lines.at(-1).id, 'new-race');
  f.close();
});

test('finished races accept finish lines and reject routine commentary', () => {
  const f = fixture();
  f.socket.receive(race('FINISHED', 10));
  f.socket.receive(line('routine', 9));
  assert.equal(f.lines.at(-1), undefined);
  f.socket.receive(line('finish', 10, 'a', 'finish'));
  assert.equal(f.lines.at(-1).id, 'finish');
  f.close();
});

test('finish commentary waits for the finished snapshot when it arrives first', () => {
  const f = fixture();
  f.socket.receive(race('STARTED', 9));
  f.socket.receive(line('finish', 10, 'a', 'finish'));
  assert.equal(f.lines.at(-1), undefined);
  f.socket.receive(race('STARTED', 9));
  assert.equal(f.lines.at(-1), undefined);
  f.socket.receive(race('FINISHED', 10));
  assert.equal(f.lines.at(-1).id, 'finish');
  f.close();
});
