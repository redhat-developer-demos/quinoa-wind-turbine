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

export function consume(status, setTeam) {
    const powerStream = new EventSource(`/api/power/stream/`);

    function getRealtimeData(n) {
        console.log(`Received: ${JSON.stringify(n)}`);
        if (n.source !== 'ping' && n.destination > 0 && n.destination <= setTeam.length) {
            setTeam[n.destination - 1]((p) => {
                const quantity = status === 'started' ? n.quantity : 0;
                return {
                    ...p,
                    [n.source]: { id: n.source, generated: p[n.source] ? p[n.source].generated + quantity : 0 }
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

const POWER_UNITS = ['W', 'KW', 'MW', 'TW'];

function resolveUnit(watts) {
    return Math.min(POWER_UNITS.length - 1, Math.floor(Math.log(watts) / Math.log(1000)));
}

export function humanPowerUnit(watts) {
    if(watts === 0) {
        return POWER_UNITS[0];
    }
    const i = resolveUnit(watts);

    return POWER_UNITS[i];
};

function computePowerValue(watts, i) {
    return (watts / Math.pow(1000, i)).toFixed(2) * 1;
}

export function humanPowerValue(watts) {
    if(watts === 0) {
        return 0;
    }
    const i = resolveUnit(watts);
    return computePowerValue(watts, i) ;
};


export function humanPower(watts) {
    if(watts === 0) {
        return `0 ${POWER_UNITS[0]}`;
    }
    const i = resolveUnit(watts);
    return computePowerValue(watts, i) + ' ' + POWER_UNITS[i];
};
