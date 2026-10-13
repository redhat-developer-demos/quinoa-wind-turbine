export function consume(setRace, setOffline) {
  let stream;
  let i = 0;
  let closed = false;
  function connect() {
    console.log('Connecting to race stream');
    stream = new EventSource('/api/race/stream');
    stream.onopen = () => {
      i = 0;
      console.log('Connected to race stream');
      setOffline(false);
    };
    stream.onmessage = (m) => setRace(JSON.parse(m.data));
    stream.onerror = (e) => {
      console.error('Disconnecting from race stream on error', e);
      stream.close();
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
    console.log('Disconnecting from race stream');
    closed = true;
    stream.close();
  };
}
