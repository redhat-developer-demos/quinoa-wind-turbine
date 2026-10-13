import { socketUrl } from './PlayerSocket';

/**
 * Renders the race from the dashboard connection, which sends the current race on connect and every snapshot after.
 */
export function consume(setRace, setOffline) {
  let socket;
  let i = 0;
  let closed = false;
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
        setRace(message.race);
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
