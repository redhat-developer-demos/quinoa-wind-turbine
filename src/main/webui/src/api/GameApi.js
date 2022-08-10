export async function assign(){
    const id = sessionStorage.getItem('user-id') || '';
    let fetchOptions = {
        method: "POST",
        headers: { 'Accept': 'application/json'},
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

export async function sendEvent(type){
    let fetchOptions = {
        method: "POST",
        body: JSON.stringify({ type }),
        headers: { 'Content-Type': 'application/json' },
    };
    return await fetch(`/api/game/event`,
        {...fetchOptions})
        .catch(e => console.error(e));
}

export const TEAM_COLORS = ['#6C84A3', 'orange'];


export function events(setStatus) {

    const onEvent = (e) => {
        console.log(`=> Received game event: ${e.type}`);
        switch (e.type) {
            case "start":
                setStatus("started");
                break;
            case "stop":
                setStatus("online");
                break;
            case "ping":
                setStatus(s => s !== "started" ? "online" : "started");
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