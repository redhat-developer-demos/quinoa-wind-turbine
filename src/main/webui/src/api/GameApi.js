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

/**
 * Updates the player's status from a game event sent over the player connection.
 */
export function applyGameEvent(e, setStatus, reset) {
  console.log(`=> Received game event: ${e.type} ${JSON.stringify(e.data)}`);
  switch (e.type) {
    case 'start':
      setStatus({ value: 'started' });
      break;
    case 'reset':
      if (reset) {
        reset();
      }
      setStatus({ value: 'paused' });
      break;
    case 'finish':
      setStatus({ value: 'finished', data: e.data });
      break;
    case 'pause':
    case 'empty':
      setStatus({ value: 'paused' });
      break;
    default:
      console.error(`invalid event type: ${e.type}`);
  }
}
