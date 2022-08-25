export async function generate(user, quantity) {
    let fetchOptions = {
        method: "POST",
        body: JSON.stringify({quantity, source: user.name, destination: user.team }),
        headers: { 'Content-Type': 'application/json' },
    };
    fetch(`/api/power`,
        {...fetchOptions})
        .catch(e => console.error(e));
}

export function consume(setTeam) {
    const powerStream = new EventSource(`/api/power/stream/`);

    function getRealtimeData(n) {
        console.log(`Received: ${JSON.stringify(n)}`);
        if (n.source !== 'ping' && n.destination > 0 && n.destination <= setTeam.length) {
            setTeam[n.destination - 1]((p) => {
                return {
                    ...p,
                    [n.source]: { id: n.source, generated: p[n.source] ? p[n.source].generated + n.quantity : 0 }
                };
            });
        }
    }

    powerStream.onmessage = m => getRealtimeData(JSON.parse(m.data));
    powerStream.onerror = (e) => {
        console.error(e);
        powerStream.close();
    }
    return () => {
        powerStream.close();
    };
}