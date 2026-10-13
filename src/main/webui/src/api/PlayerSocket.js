// Taps are summed and sent at most this often, instead of one request per tap
const BATCH_INTERVAL_MS = 200;
// The server ignores batches above this, like the REST endpoint does
const MAX_BATCH = 200;

export function socketUrl(path) {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${window.location.host}${path}`;
}

/**
 * Opens the player connection and reconnects when it drops.
 * handlers: onEvent(gameEvent), onStanding(standing), onNotification(text), onOffline()
 */
export function connect(user, handlers) {
  let socket;
  let closed = false;
  let retries = 0;
  let pending = 0;
  let flushTimeout;

  function flush() {
    clearTimeout(flushTimeout);
    flushTimeout = null;
    if (pending > 0 && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ quantity: pending }));
      pending = 0;
    }
  }

  function open() {
    socket = new WebSocket(socketUrl(`/ws/player/${user.id}?team=${user.team}`));
    socket.onopen = () => {
      retries = 0;
      flush();
    };
    socket.onmessage = (m) => {
      const message = JSON.parse(m.data);
      switch (message.type) {
        case 'status':
          handlers.onEvent(message.event);
          break;
        case 'standing':
          handlers.onStanding(message.standing);
          break;
        case 'notification':
          handlers.onNotification(message.text);
          break;
        default:
          console.error(`invalid player message type: ${message.type}`);
      }
    };
    socket.onclose = () => {
      handlers.onOffline();
      if (!closed) {
        retries += 1;
        setTimeout(open, Math.min(10000, retries * 1000));
      }
    };
  }

  open();
  return {
    generate(quantity) {
      if (pending + quantity > MAX_BATCH) {
        flush();
      }
      pending = Math.min(MAX_BATCH, pending + quantity);
      if (!flushTimeout) {
        flushTimeout = setTimeout(flush, BATCH_INTERVAL_MS);
      }
    },
    close() {
      closed = true;
      clearTimeout(flushTimeout);
      socket.close();
    },
  };
}
