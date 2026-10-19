export const DEV_MODE = !process.env.NODE_ENV || process.env.NODE_ENV === 'development';

export const IS_TOUCH_DEVICE = (('ontouchstart' in window)
    || (navigator.maxTouchPoints > 0)
    || (navigator.msMaxTouchPoints > 0));

// available cars images: 'car-classic-black', 'car-barbie', 'car-grey', 'car-red', 'car-blue-convertible', 'car-orange'
// Loaded before rendering; application.properties is shared with server commentary.
export let TEAMS_CONFIG = [];

export async function loadConfig() {
  const response = await fetch('/api/config');
  if (!response.ok) throw new Error('Cannot load race configuration');
  const config = await response.json();
  if (!Array.isArray(config.teams) || config.teams.length !== 2
      || config.teams.some((team, i) => team.id !== i + 1 || !team.name || !team.color || !team.car)) {
    throw new Error('Invalid race team configuration');
  }
  TEAMS_CONFIG = config.teams;
}

// Dashboard
export const TAP_POWER = 15; // in MW
export const NB_TAP_NEEDED_PER_USER = 100;
export const SHOW_TOP = 5;

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
