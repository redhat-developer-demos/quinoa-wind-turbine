export const DEV_MODE = !process.env.NODE_ENV || process.env.NODE_ENV === 'development';

export const IS_TOUCH_DEVICE = (('ontouchstart' in window)
    || (navigator.maxTouchPoints > 0)
    || (navigator.msMaxTouchPoints > 0));

// Each team has a `color` (the F1 car's body) and an optional `accent` (its livery stripes and wing
// elements, white when unset). `car` names a legacy sprite id that the validator still requires.
// Loaded before rendering; application.properties is shared with server commentary.
export let TEAMS_CONFIG = [];
// The active theme's track from the server, or null to use the dashboard's default track.
export let CURRENT_TRACK = null;

export async function loadConfig() {
  const response = await fetch('/api/config');
  if (!response.ok) throw new Error('Cannot load race configuration');
  const config = await response.json();
  if (!Array.isArray(config.teams) || config.teams.length !== 2
      || config.teams.some((team, i) => team.id !== i + 1 || !team.name || !team.color || !team.car)) {
    throw new Error('Invalid race team configuration');
  }
  TEAMS_CONFIG = config.teams;
  CURRENT_TRACK = config.track || null;
}

// Dashboard
export const TAP_POWER = 15; // in MW
export const NB_TAP_NEEDED_PER_USER = 100;
export const SHOW_TOP = 5;
// The winner popup's leaderboards cap at this many rows per column, so a race with hundreds of
// players doesn't render a list nobody scrolls. Separate from SHOW_TOP, which caps the sidebar.
export const SHOW_TOP_WINNER = 10;

// Mobile app
export const ENABLE_TAPPING = true;
export const ENABLE_SHAKING = false; // 'false' in v1, set to 'true' in v2
export const ENABLE_BLOWING = false;
export const ENABLE_SWIPING = false;

// LOGGING
console.log(`devMode: ${DEV_MODE}`);
console.log(`Tap needed: ${NB_TAP_NEEDED_PER_USER}`);
console.log('Swiping Sensor: ', ENABLE_SWIPING);
console.log('Tapping Sensor: ', ENABLE_TAPPING);
console.log('Blowing Sensor: ', ENABLE_BLOWING);
console.log('Shaking Sensor: ', ENABLE_SHAKING);
