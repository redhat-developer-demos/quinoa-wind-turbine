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

export function consume(setPower, setTeam) {
    const powerStream = new EventSource(`/api/power/stream/`);

    function getRealtimeData(n) {
        console.log(JSON.stringify(n));
        if (n.source !== 'ping' && n.destination > 0 && n.destination <= setPower.length) {
            setPower[n.destination - 1]((p) => p + n.quantity);
            setTeam[n.destination - 1]((p) => new Set([...Array.from(p), n.source]));
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