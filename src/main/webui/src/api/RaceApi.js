import { socketUrl } from './PlayerSocket.js';

/**
 * Renders the race from the dashboard connection, which sends the current race on connect and every snapshot after.
 */
export function consume(setRace, setOffline, setCommentary = () => {}, onTheme = () => {}) {
  let socket;
  let i = 0;
  let closed = false;
  let currentRace;
  let pendingCommentary;
  let latestCommentary;
  const seen = new Set();

  function receiveCommentary(line) {
    if (!line || !line.id || typeof line.text !== 'string' || !line.text.trim()) {
      return;
    }
    // The cached line can arrive before the initial race snapshot on reconnect.
    if (!currentRace) {
      pendingCommentary = line;
      return;
    }
    if (line.raceId !== currentRace.raceId) {
      pendingCommentary = line;
      return;
    }
    if (seen.has(line.id)
        || (latestCommentary && line.snapshotAt < latestCommentary.snapshotAt)) {
      return;
    }
    if (line.trigger === 'finish' && currentRace.status !== 'FINISHED') {
      pendingCommentary = line;
      return;
    }
    if (currentRace.status === 'WAITING' || currentRace.status === 'PAUSED'
        || (currentRace.status === 'FINISHED' && line.trigger !== 'finish')) {
      if (line.snapshotAt > currentRace.updatedAt) {
        pendingCommentary = line;
      }
      return;
    }
    seen.add(line.id);
    latestCommentary = line;
    setCommentary(line);
  }

  function connect() {
    console.log('Connecting to dashboard socket');
    socket = new WebSocket(socketUrl('/ws/dashboard'));
    socket.onopen = () => {
      i = 0;
      console.log('Connected to dashboard socket');
      setOffline(false);
    };
    socket.onmessage = (m) => {
      const message = JSON.parse(m.data);
      if (message.type === 'race') {
        if (!currentRace || message.race.raceId !== currentRace.raceId
            || message.race.status === 'WAITING') {
          seen.clear();
          latestCommentary = undefined;
          setCommentary(undefined);
        }
        currentRace = message.race;
        setRace(message.race);
        if (pendingCommentary) {
          const pending = pendingCommentary;
          pendingCommentary = undefined;
          receiveCommentary(pending);
        }
      } else if (message.type === 'commentary') {
        receiveCommentary(message.commentary);
      } else if (message.type === 'game' && message.event.type === 'reset') {
        pendingCommentary = undefined;
        latestCommentary = undefined;
        seen.clear();
        // Ignore old commentary until the reset snapshot arrives.
        if (currentRace) {
          currentRace = { ...currentRace, status: 'WAITING' };
        }
        setCommentary(undefined);
      } else if (message.type === 'game' && message.event.type === 'theme') {
        onTheme();
      }
    };
    socket.onclose = (e) => {
      console.error('Dashboard socket closed', e.code);
      if (i > 0) {
        setOffline(true);
      }
      if (!closed && i < 300) {
        i += 1;
        setTimeout(connect, 2000);
      }
    };
  }
  connect();
  return () => {
    console.log('Disconnecting from dashboard socket');
    closed = true;
    socket.close();
  };
}
