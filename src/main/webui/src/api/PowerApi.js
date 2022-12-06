import _ from 'lodash';

export async function generate(user, quantity) {
  const fetchOptions = {
    method: 'POST',
    body: JSON.stringify({ quantity, source: user.name, destination: user.team }),
    headers: { 'Content-Type': 'application/json' },
  };
  fetch(
    '/api/power',
    { ...fetchOptions },
  )
    .catch((e) => console.error(e));
}

export function consume(status, setTeam) {
  console.log('Connecting to power stream');
  const powerStream = new EventSource('/api/power/stream/');

  function getRealtimeData(b) {
    console.log(`Received ${b.length} events`);
    const teams = [{}, {}];
    for (const i of b.filter((e) => e.source !== 'ping' && e.destination > 0 && e.destination <= setTeam.length)) {
      const quantity = status === 'started' ? i.quantity : 0;
      if (!teams[i.destination - 1][i.source]) {
        teams[i.destination - 1][i.source] = 0;
      }
      teams[i.destination - 1][i.source] += quantity;
    }

    for (const i in teams) {
      const team = teams[i];
      if (_.size(team) > 0) {
        setTeam[i]((p) => {
          const n = { ...p };
          for (const e in team) {
            if (!n[e]) {
              n[e] = { name: e, generated: 0 };
            }
            n[e].generated += team[e];
          }
          return n;
        });
      }
    }
  }

  powerStream.onmessage = (m) => getRealtimeData(JSON.parse(m.data));
  powerStream.onerror = (e) => {
    console.error('Disconnecting from power event stream on error', e);
    powerStream.close();
  };
  return () => {
    console.log('Disconnecting from power event stream');
    powerStream.close();
  };
}

const POWER_UNITS = ['W', 'KW', 'MW', 'TW'];

function resolveUnit(watts) {
  return Math.min(POWER_UNITS.length - 1, Math.floor(Math.log(watts) / Math.log(1000)));
}

export function humanPowerUnit(watts) {
  if (watts === 0) {
    return POWER_UNITS[0];
  }
  const i = resolveUnit(watts);

  return POWER_UNITS[i];
}

function computePowerValue(watts, i) {
  return (watts / 1000 ** i).toFixed(2) * 1;
}

export function humanPowerValue(watts) {
  if (watts === 0) {
    return 0;
  }
  const i = resolveUnit(watts);
  return computePowerValue(watts, i);
}

export function humanPower(watts) {
  if (watts === 0) {
    return `0 ${POWER_UNITS[0]}`;
  }
  const i = resolveUnit(watts);
  return `${computePowerValue(watts, i)} ${POWER_UNITS[i]}`;
}
