export async function assign(){
    let fetchOptions = {
        method: "POST",
        headers: { 'Accept': 'application/json' },
    };
    return await fetch(`/api/game/assign`,
        {...fetchOptions})
        .catch(e => console.error(e))
        .then(r => r.json());
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


export function events(onEvent) {
    const stream = new EventSource(`/api/game/events/`);

    stream.onmessage = m => onEvent(JSON.parse(m.data));
    stream.onerror = (e) => {
        console.error(e);
        stream.close();
    }
    return () => {
        stream.close();
    };
}