const mode = !process.env.NODE_ENV || process.env.NODE_ENV === 'development';
const storage = mode ? sessionStorage : localStorage;

console.log(`${mode}mode`);

export async function assign() {
    let id = storage.getItem('user-id');
    if(!id || id === 'undefined') {
        id = '';
    }
    let fetchOptions = {
        method: "POST",
        headers: {'Accept': 'application/json'},
    };
    return await fetch(`/api/game/assign/${id}`,
        {...fetchOptions})
        .then(convertResponse)
        .then(u => {
            if(u.id) {
                storage.setItem('user-id', u.id);
            }
            return u;
        }).catch(e => console.error(e));
}

export async function sendEvent(type) {
    let fetchOptions = {
        method: "POST",
        body: JSON.stringify({type}),
        headers: {'Content-Type': 'application/json'},
    };
    return await fetch(`/api/game/event`,
        {...fetchOptions})
        .catch(e => console.error(e));
}

export function events(setStatus, reset) {

    const onEvent = (e) => {
        console.log(`=> Received game event: ${e.type}`);
        switch (e.type) {
            case 'start':
                setStatus('started');
                break;
            case 'reset':
                reset();
                setStatus('initial');
                break;
            case 'pause':
                setStatus('paused');
                break;
        }
    }
    let stream;
    let i = 0;
    function connect() {
        console.log('Connecting to game event stream');
        stream = new EventSource(`/api/game/events/`);
        stream.onopen = () => {
            i = 0;
            console.log('Connected to game event stream');
            setStatus(p => {
                if (p === 'offline') {
                    return 'initial';
                }
                return p;
            });
        };
        stream.onmessage = m => onEvent(JSON.parse(m.data));
        stream.onerror = (e) => {
            console.error('Disconnecting from game event stream on error', e);
            stream.close();
            if(i > 0) {
                setStatus('offline');
            }
            if (i++ < 300) {
                setTimeout(connect, 2000);
            }
        };
    }
    connect();
    return () => {
        if (stream) {
            console.log('Disconnecting from game event stream');
            stream.close();
        }
    };
}

function convertResponse(response) {
    if (response.ok) {
        return response.json();
    }
    throw new Error(`Request error: ${response.status}`);
}

export function status(setStatus, reset) {
    let timeout = undefined;
    const onEvent = (e) => {
        if (e) {
            console.log(`=> Received game event: ${e.type}`);
            switch (e.type) {
                case 'start':
                    setStatus("started");
                    break;
                case 'reset':
                    if (reset) {
                        reset();
                    }
                case 'pause':
                    setStatus("paused");
                    break;
                case "empty":
                    setStatus(s => s !== "started" ? "paused" : "started");
                    break;
            }
        }
        timeout = setTimeout(fetchStatus, 1000);
    }
    const fetchStatus = () => {
        fetch('/api/game/status')
            .then(convertResponse)
            .then(onEvent)
            .catch(e => {
                console.error(e);
                setStatus('offline');
                onEvent();
            });
    };
    fetchStatus();
    return () => {
        if (timeout) {
            clearTimeout(timeout);
            timeout = null;
        }
    }
}