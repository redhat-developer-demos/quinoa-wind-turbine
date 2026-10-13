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
