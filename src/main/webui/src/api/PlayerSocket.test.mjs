import test from 'node:test';
import assert from 'node:assert/strict';
import { connect } from './PlayerSocket.js';

class MockSocket {
  static last;
  static OPEN = 1;

  constructor(url) {
    MockSocket.last = this;
    this.url = url;
    this.readyState = MockSocket.OPEN;
    this.sent = [];
    setTimeout(() => {
      if (this.onopen) this.onopen();
    }, 0);
  }

  send(data) {
    this.sent.push(JSON.parse(data));
  }

  close() {
    if (this.onclose) this.onclose();
  }

  receive(message) {
    this.onmessage({ data: JSON.stringify(message) });
  }
}

test('PlayerSocket handlers are invoked on socket messages and events', async () => {
  globalThis.WebSocket = MockSocket;
  globalThis.window = { location: { protocol: 'http:', host: 'localhost:8080' } };

  let opened = false;
  let receivedEvent = null;
  let receivedInputMode = null;
  let receivedStanding = null;
  let receivedNotification = null;

  const conn = connect({ id: 1, team: 1 }, {
    onOpen: () => { opened = true; },
    onEvent: (e) => { receivedEvent = e; },
    onInputMode: (mode) => { receivedInputMode = mode; },
    onStanding: (s) => { receivedStanding = s; },
    onNotification: (n) => { receivedNotification = n; },
    onOffline: () => {},
  });

  // Wait for open
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(opened, true);

  const socket = MockSocket.last;

  // Status with input-mode event
  socket.receive({ type: 'status', event: { type: 'input-mode', data: { mode: 'shake' } } });
  assert.deepEqual(receivedEvent, { type: 'input-mode', data: { mode: 'shake' } });
  assert.equal(receivedInputMode, 'shake');

  // Standing
  socket.receive({ type: 'standing', standing: { teamPosition: 1, rankInTeam: 1 } });
  assert.deepEqual(receivedStanding, { teamPosition: 1, rankInTeam: 1 });

  // Notification
  socket.receive({ type: 'notification', text: 'You took the lead!' });
  assert.equal(receivedNotification, 'You took the lead!');

  conn.close();
});
