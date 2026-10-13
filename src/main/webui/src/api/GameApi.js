import { DEV_MODE } from '../Config';

const storage = DEV_MODE ? sessionStorage : localStorage;

function convertResponse(response) {
  if (response.ok) {
    return response.json();
  }
  throw new Error(`Request error: ${response.status}`);
}

export async function assign(team) {
  let id = storage.getItem('user-id');
  if (!id || id === 'undefined') {
    id = '';
  }
  const fetchOptions = {
    method: 'POST',
    headers: { Accept: 'application/json' },
  };
  return fetch(
    `/api/game/assign/${id}?team=${team}`,
    { ...fetchOptions },
  )
    .then(convertResponse)
    .then((u) => {
      if (u.id) {
        storage.setItem('user-id', u.id);
      }
      return u;
    }).catch((e) => console.error(e));
}

export async function sendEvent(type, data) {
  const fetchOptions = {
    method: 'POST',
    body: JSON.stringify({ type, data: data || {} }),
    headers: { 'Content-Type': 'application/json' },
  };
  return fetch(
    '/api/game/event',
    { ...fetchOptions },
  ).catch((e) => console.error(e));
}

class StatusHandler {
  constructor(setStatus, reset) {
    this.setStatus = setStatus;
    this.reset = reset;
  }

  fetchStatus = () => {
    fetch('/api/game/status')
      .then(convertResponse)
      .then(this.onEvent)
      .catch((e) => {
        console.error(e);
        this.setStatus('offline');
        this.onEvent();
      });
  };

  onEvent = (e) => {
    if (e) {
      console.log(`=> Received game event: ${e.type} ${JSON.stringify(e.data)}`);
      switch (e.type) {
        case 'start':
          this.setStatus({ value: 'started' });
          break;
        case 'reset':
          if (this.reset) {
            this.reset();
          }
          this.setStatus({ value: 'paused' });
          break;
        case 'finish':
          this.setStatus({ value: 'finished', data: e.data });
          break;
        case 'pause':
          this.setStatus({ value: 'paused' });
          break;
        case 'finish':
          this.setStatus({ value: 'finished', data: e.data });
          break;
        case 'empty':
          this.setStatus((s) => (s !== 'started' ? { value: 'paused' } : { value: 'started' }));
          break;
        default:
          console.error(`invalid event type: ${e.type}`);
      }
    }
    this.timeout = setTimeout(this.fetchStatus, 1000);
  };

  clear = () => {
    if (this.timeout) {
      clearTimeout(this.timeout);
      this.timeout = null;
    }
  };
}

export function status(setStatus, reset) {
  const statusHandler = new StatusHandler(setStatus, reset);
  statusHandler.fetchStatus();
  return statusHandler.clear;
}
