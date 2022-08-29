export async function assign() {
    const id = sessionStorage.getItem('user-id') || '';
    let fetchOptions = {
        method: "POST",
        headers: {'Accept': 'application/json'},
    };
    return await fetch(`/api/game/assign/${id}`,
        {...fetchOptions})
        .catch(e => console.error(e))
        .then(r => r.json())
        .then(u => {
            sessionStorage.setItem('user-id', u.id);
            return u;
        });
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

export const TEAM_COLORS = ['#6C84A3', 'orange'];

export function events(setStatus, reset) {

    const onEvent = (e) => {
        console.log(`=> Received game event: ${e.type}`);
        switch (e.type) {
            case 'start':
                setStatus('started');
                break;
            case 'reset':
                if (reset) {
                    reset();
                }
            case 'pause':
                setStatus('paused');
                break;
            case 'ping':
                setStatus(s => s !== 'started' ? 'paused' : 'started');
                break;
        }
    }
    const stream = new EventSource(`/api/game/events/`);
    stream.onmessage = m => onEvent(JSON.parse(m.data));
    stream.onerror = (e) => {
        console.error("Error while receiving events: " + JSON.stringify(e));
    }
    return () => {
        stream.close();
    };
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
            .catch(e => console.error("Error while receiving events: " + JSON.stringify(e)))
            .then(r => r.json())
            .then(onEvent);
    };
    fetchStatus();
    return () => {
        if (timeout) {
            clearTimeout(timeout);
            timeout = null;
        }
    }
}